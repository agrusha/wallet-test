package com.gowiper.wallet.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.*;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.content.LocalBroadcastManager;
import android.text.format.DateUtils;
import com.gowiper.wallet.Configuration;
import com.gowiper.wallet.Constants;
import com.gowiper.wallet.CrashReporter;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.data.BlockchainState;
import com.gowiper.wallet.data.BlockchainState.Impediment;
import com.gowiper.wallet.util.AddressBookProvider;
import com.gowiper.wallet.util.ThrottlingWalletChangeListener;
import com.gowiper.wallet.util.WalletUtils;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.net.discovery.PeerDiscoveryException;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.utils.Threading;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Andreas Schildbach
 */

@Slf4j
public class BlockchainServiceImpl extends android.app.Service implements BlockchainService {
    private WalletClient walletCLient;
    private Configuration config;

    private BlockStore blockStore;
    private File blockChainFile;
    private BlockChain blockChain;
    @CheckForNull
    private PeerGroup peerGroup;

    private final Handler handler = new Handler();
    private final Handler delayHandler = new Handler();
    private WakeLock wakeLock;

    private NotificationManager nm;
    private static final int NOTIFICATION_ID_COINS_RECEIVED = 1;

    private final Set<Impediment> impediments = EnumSet.noneOf(Impediment.class);
    private int notificationCount = 0;
    private Coin notificationAccumulatedAmount = Coin.ZERO;
    private final List<Address> notificationAddresses = new LinkedList<Address>();
    private long serviceCreatedAt;
    private boolean resetBlockchainOnShutdown = false;

    private static final long APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
    private static final long BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    private final WalletEventListener walletEventListener = new ThrottlingWalletChangeListener(APPWIDGET_THROTTLE_MS) {
        @Override
        public void onThrottledWalletChanged() {
            // TODO update view
//            WalletBalanceWidgetProvider.updateWidgets(BlockchainServiceImpl.this, walletCLient.getWallet());
        }

        @Override
        public void onCoinsReceived(final Wallet wallet, final Transaction tx, final Coin prevBalance, final Coin newBalance) {
            final int bestChainHeight = blockChain.getBestChainHeight();

            final Address from = WalletUtils.getFirstFromAddress(tx);
            final Coin amount = tx.getValue(wallet);
            final ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();

            handler.post(new Runnable() {
                @Override
                public void run() {
                    final boolean isReceived = amount.signum() > 0;
                    final boolean replaying = bestChainHeight < config.getBestChainHeightEver();
                    final boolean isReplayedTx = confidenceType == ConfidenceType.BUILDING && replaying;

                    if (isReceived && !isReplayedTx) {
                        notifyCoinsReceived(from, amount);
                    }
                }
            });
        }

    };

    private void notifyCoinsReceived(@Nullable final Address from, @Nonnull final Coin amount) {
        if (notificationCount == 1) {
            nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);
        }

        notificationCount++;
        notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount);
        if (from != null && !notificationAddresses.contains(from)) {
            notificationAddresses.add(from);
        }

        final MonetaryFormat btcFormat = config.getFormat();

        final String packageFlavor = walletCLient.applicationPackageFlavor();
        final String msgSuffix = packageFlavor != null ? " [" + packageFlavor + "]" : "";

        final String tickerMsg = "Received "+ btcFormat.format(amount) + msgSuffix; //getString(R.string.notification_coins_received_msg, btcFormat.format(amount)) + msgSuffix;
        final String msg = "Received "+ btcFormat.format(notificationAccumulatedAmount) + msgSuffix; //getString(R.string.notification_coins_received_msg, btcFormat.format(notificationAccumulatedAmount)) + msgSuffix;

        final StringBuilder text = new StringBuilder();
        for (final Address address : notificationAddresses) {
            if (text.length() > 0) {
                text.append(", ");
            }

            final String addressStr = address.toString();
            final String label = AddressBookProvider.resolveLabel(getApplicationContext(), addressStr);
            text.append(label != null ? label : addressStr);
        }

        final Notification.Builder notification = new Notification.Builder(this);
//        notification.setSmallIcon(R.drawable.stat_notify_received);
        notification.setTicker(tickerMsg);
        notification.setContentTitle(msg);
        if (text.length() > 0) {
            notification.setContentText(text);
        }
//        notification.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, WalletActivity.class), 0));
        notification.setNumber(notificationCount == 1 ? 0 : notificationCount);
        notification.setWhen(System.currentTimeMillis());
//        notification.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received));
        nm.notify(NOTIFICATION_ID_COINS_RECEIVED, notification.getNotification());
    }

    private final PeerEventListener blockchainDownloadListener = new AbstractPeerEventListener() {
        private final AtomicLong lastMessageTime = new AtomicLong(0);

        @Override
        public void onBlocksDownloaded(final Peer peer, final Block block, final int blocksLeft) {
            config.maybeIncrementBestChainHeightEver(blockChain.getChainHead().getHeight());

            delayHandler.removeCallbacksAndMessages(null);

            final long now = System.currentTimeMillis();

            if (now - lastMessageTime.get() > BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS) {
                delayHandler.post(runnable);
            } else {
                delayHandler.postDelayed(runnable, BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS);
            }
        }

        private final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                lastMessageTime.set(System.currentTimeMillis());

                broadcastBlockchainState();
            }
        };
    };

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();

            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                final boolean hasConnectivity = !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                log.info("network is " + (hasConnectivity ? "up" : "down"));

                if (hasConnectivity) {
                    impediments.remove(Impediment.NETWORK);
                } else {
                    impediments.add(Impediment.NETWORK);
                }
                check();
            } else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                log.info("device storage low");

                impediments.add(Impediment.STORAGE);
                check();
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                log.info("device storage ok");

                impediments.remove(Impediment.STORAGE);
                check();
            }
        }

        @SuppressLint("Wakelock")
        private void check() {
            final Wallet wallet = walletCLient.getWallet();

            if (impediments.isEmpty() && peerGroup == null) {
                log.debug("acquiring wakelock");
                wakeLock.acquire();

                // consistency check
                final int walletLastBlockSeenHeight = wallet.getLastBlockSeenHeight();
                final int bestChainHeight = blockChain.getBestChainHeight();
                if (walletLastBlockSeenHeight != -1 && walletLastBlockSeenHeight != bestChainHeight) {
                    final String message = "wallet/blockchain out of sync: " + walletLastBlockSeenHeight + "/" + bestChainHeight;
                    log.error(message);
                    CrashReporter.saveBackgroundTrace(new RuntimeException(message), walletCLient.packageInfo());
                }

                log.info("starting peergroup");
                peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, blockChain);
                peerGroup.setDownloadTxDependencies(false); // recursive implementation causes StackOverflowError
                peerGroup.addWallet(wallet);
                peerGroup.setUserAgent(Constants.USER_AGENT, walletCLient.packageInfo().versionName);

                final int maxConnectedPeers = walletCLient.maxConnectedPeers();

                final String trustedPeerHost = config.getTrustedPeerHost();
                final boolean hasTrustedPeer = !trustedPeerHost.isEmpty();

                final boolean connectTrustedPeerOnly = hasTrustedPeer && config.getTrustedPeerOnly();
                peerGroup.setMaxConnections(connectTrustedPeerOnly ? 1 : maxConnectedPeers);
                peerGroup.setConnectTimeoutMillis(Constants.PEER_TIMEOUT_MS);

                peerGroup.addPeerDiscovery(new PeerDiscovery() {
                    private final PeerDiscovery normalPeerDiscovery = new DnsDiscovery(Constants.NETWORK_PARAMETERS);

                    @Override
                    public InetSocketAddress[] getPeers(final long timeoutValue, final TimeUnit timeoutUnit) throws PeerDiscoveryException {
                        final List<InetSocketAddress> peers = new LinkedList<InetSocketAddress>();

                        boolean needsTrimPeersWorkaround = false;

                        if (hasTrustedPeer) {
                            log.info("trusted peer '" + trustedPeerHost + "'" + (connectTrustedPeerOnly ? " only" : ""));

                            final InetSocketAddress addr = new InetSocketAddress(trustedPeerHost, Constants.NETWORK_PARAMETERS.getPort());
                            if (addr.getAddress() != null) {
                                peers.add(addr);
                                needsTrimPeersWorkaround = true;
                            }
                        }

                        if (!connectTrustedPeerOnly) {
                            peers.addAll(Arrays.asList(normalPeerDiscovery.getPeers(timeoutValue, timeoutUnit)));
                        }

                        // workaround because PeerGroup will shuffle peers
                        if (needsTrimPeersWorkaround) {
                            while (peers.size() >= maxConnectedPeers)
                                peers.remove(peers.size() - 1);
                        }

                        return peers.toArray(new InetSocketAddress[0]);
                    }

                    @Override
                    public void shutdown() {
                        normalPeerDiscovery.shutdown();
                    }
                });

                // start peergroup
                peerGroup.startAsync();
                peerGroup.startBlockChainDownload(blockchainDownloadListener);
            } else if (!impediments.isEmpty() && peerGroup != null) {
                log.info("stopping peergroup");
                peerGroup.removeWallet(wallet);
                peerGroup.stopAsync();
                peerGroup = null;

                log.debug("releasing wakelock");
                wakeLock.release();
            }

            broadcastBlockchainState();
        }
    };

    public class LocalBinder extends Binder {
        public BlockchainService getService() {
            return BlockchainServiceImpl.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(final Intent intent) {
        log.debug(".onBind()");

        return mBinder;
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        log.debug(".onUnbind()");

        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        serviceCreatedAt = System.currentTimeMillis();
        log.debug(".onCreate()");

        super.onCreate();

        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        final String lockName = getPackageName() + " blockchain sync";

        final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);

        walletCLient = WalletClient.getInstance(this.getApplicationContext());
        config = walletCLient.getConfiguration();
        final Wallet wallet = walletCLient.getWallet();

        blockChainFile = new File(getDir("blockstore", Context.MODE_PRIVATE), Constants.Files.BLOCKCHAIN_FILENAME);
        final boolean blockChainFileExists = blockChainFile.exists();

        if (!blockChainFileExists) {
            log.info("blockchain does not exist, resetting wallet");

            wallet.clearTransactions(0);
            wallet.setLastBlockSeenHeight(-1); // magic value
            wallet.setLastBlockSeenHash(null);
        }

        try {
            blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
            blockStore.getChainHead(); // detect corruptions as early as possible

            final long earliestKeyCreationTime = wallet.getEarliestKeyCreationTime();

            if (!blockChainFileExists && earliestKeyCreationTime > 0) {
                try {
                    final InputStream checkpointsInputStream = getAssets().open(Constants.Files.CHECKPOINTS_FILENAME);
                    CheckpointManager.checkpoint(Constants.NETWORK_PARAMETERS, checkpointsInputStream, blockStore, earliestKeyCreationTime);
                } catch (final IOException x) {
                    log.error("problem reading checkpoints, continuing without", x);
                }
            }
        } catch (final BlockStoreException x) {
            blockChainFile.delete();

            final String msg = "blockstore cannot be created";
            log.error(msg, x);
            throw new Error(msg, x);
        }

        try {
            blockChain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
        } catch (final BlockStoreException x) {
            throw new Error("blockchain cannot be created", x);
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        registerReceiver(connectivityReceiver, intentFilter); // implicitly start PeerGroup

        walletCLient.getWallet().addEventListener(walletEventListener, Threading.SAME_THREAD);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent != null) {
            log.info("service start command: " + intent
                    + (intent.hasExtra(Intent.EXTRA_ALARM_COUNT) ? " (alarm count: " + intent.getIntExtra(Intent.EXTRA_ALARM_COUNT, 0) + ")" : ""));

            final String action = intent.getAction();

            if (BlockchainService.ACTION_CANCEL_COINS_RECEIVED.equals(action)) {
                notificationCount = 0;
                notificationAccumulatedAmount = Coin.ZERO;
                notificationAddresses.clear();

                nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);
            } else if (BlockchainService.ACTION_RESET_BLOCKCHAIN.equals(action)) {
                log.info("will remove blockchain on service shutdown");

                resetBlockchainOnShutdown = true;
                stopSelf();
            } else if (BlockchainService.ACTION_BROADCAST_TRANSACTION.equals(action)) {
                final Sha256Hash hash = new Sha256Hash(intent.getByteArrayExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH));
                final Transaction tx = walletCLient.getWallet().getTransaction(hash);

                if (peerGroup != null) {
                    log.info("broadcasting transaction " + tx.getHashAsString());
                    peerGroup.broadcastTransaction(tx);
                } else {
                    log.info("peergroup not available, not broadcasting transaction " + tx.getHashAsString());
                }
            }
        } else {
            log.warn("service restart, although it was started as non-sticky");
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        log.debug(".onDestroy()");

        WalletClient.scheduleStartBlockchainService(this);

        walletCLient.getWallet().removeEventListener(walletEventListener);

        unregisterReceiver(connectivityReceiver);

        if (peerGroup != null) {
            peerGroup.removeWallet(walletCLient.getWallet());
            peerGroup.stopAsync();
            peerGroup.awaitTerminated();

            log.info("peergroup stopped");
        }

        delayHandler.removeCallbacksAndMessages(null);

        try {
            blockStore.close();
        } catch (final BlockStoreException x) {
            throw new RuntimeException(x);
        }

        walletCLient.saveWallet();

        if (wakeLock.isHeld()) {
            log.debug("wakelock still held, releasing");
            wakeLock.release();
        }

        if (resetBlockchainOnShutdown) {
            log.info("removing blockchain");
            blockChainFile.delete();
        }

        super.onDestroy();

        log.info("service was up for " + ((System.currentTimeMillis() - serviceCreatedAt) / 1000 / 60) + " minutes");
    }

    @Override
    public void onTrimMemory(final int level) {
        log.info("onTrimMemory({}) called", level);

        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            log.warn("low memory detected, stopping service");
            stopSelf();
        }
    }

    @Override
    public BlockchainState getBlockchainState() {
        final StoredBlock chainHead = blockChain.getChainHead();
        final Date bestChainDate = chainHead.getHeader().getTime();
        final int bestChainHeight = chainHead.getHeight();
        final boolean replaying = chainHead.getHeight() < config.getBestChainHeightEver();

        return new BlockchainState(bestChainDate, bestChainHeight, replaying, impediments);
    }

    @Override
    public List<Peer> getConnectedPeers() {
        if (peerGroup != null) {
            return peerGroup.getConnectedPeers();
        } else {
            return null;
        }
    }

    @Override
    public List<StoredBlock> getRecentBlocks(final int maxBlocks) {
        final List<StoredBlock> blocks = new ArrayList<StoredBlock>(maxBlocks);

        try {
            StoredBlock block = blockChain.getChainHead();

            while (block != null) {
                blocks.add(block);

                if (blocks.size() >= maxBlocks) {
                    break;
                }

                block = block.getPrev(blockStore);
            }
        } catch (final BlockStoreException x) {
            // swallow
        }

        return blocks;
    }

    private void broadcastBlockchainState() {
        final Intent broadcast = new Intent(ACTION_BLOCKCHAIN_STATE);
        broadcast.setPackage(getPackageName());
        getBlockchainState().putExtras(broadcast);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }
}
