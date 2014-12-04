package de.schildbach.wallet.wallet;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.gowiper.utils.SimpleThreadFactory;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;
import de.schildbach.wallet.util.CrashReporter;
import de.schildbach.wallet.util.GuiThreadExecutor;
import de.schildbach.wallet.util.LinuxSecureRandom;
import lombok.Delegate;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.utils.Threading;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.concurrent.Executors;

@Slf4j
public class WalletClient {
    @Getter private final Context applicationContext;
    private final ActivityManager activityManager;
    @Getter private final Configuration configuration;
    @Delegate private final BlockchainServiceController blockchainServiceController;
    private final WalletController walletController;
    @Getter private final TransactionController transactionController;
    @Getter private final BlockchainManager blockchainManager;
    @Getter private final ListeningScheduledExecutorService backgroundExecutor;
    @Getter private final GuiThreadExecutor guiThreadExecutor;
    private PackageInfo packageInfo;

    public WalletClient(Context context) {
        this.applicationContext = context;
        new LinuxSecureRandom(); // init proper random number generator
        initCrashReporter();
        initMnemonicCode();
        this.packageInfo = packageInfoFromContext(applicationContext);
        this.backgroundExecutor = MoreExecutors.listeningDecorator(
                Executors.newScheduledThreadPool(2, SimpleThreadFactory.create("Scheduler", true)));
        this.guiThreadExecutor = GuiThreadExecutor.getInstance();
        this.configuration = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));
        this.activityManager = (ActivityManager) applicationContext.getSystemService(Context.ACTIVITY_SERVICE);
        this.blockchainServiceController = new BlockchainServiceControllerImpl(context.getApplicationContext());

        this.walletController = new WalletControllerImpl(context.getApplicationContext(), configuration);
        this.transactionController = new TransactionController(this);
        this.blockchainManager = new BlockchainManagerImpl(this);

        this.configuration.updateLastVersionCode(packageInfo.versionCode);
    }

    private void initCrashReporter() {
        Threading.throwOnLockCycles();
        CrashReporter.init(applicationContext.getCacheDir());
        Threading.uncaughtExceptionHandler = new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread thread, final Throwable throwable) {
                log.info("bitcoinj uncaught exception", throwable);
                CrashReporter.saveBackgroundTrace(throwable, packageInfo);
            }
        };
    }

    private void initMnemonicCode() {
        try {
            MnemonicCode.INSTANCE = new MnemonicCode(applicationContext.getAssets().open("bip39-wordlist.txt"), null);
        } catch (final IOException x) {
            throw new Error(x);
        }
    }

    public Wallet getWallet() {
        return walletController.getWallet();
    }

    public void saveWallet() {
        walletController.saveWallet();
    }

    public void replaceWallet(final Wallet newWallet) {
        walletController.replaceWallet(newWallet);
        resetBlockchainService();
    }

    public void processDirectTransaction(@Nonnull final Transaction tx) throws VerificationException {
        Wallet wallet = walletController.getWallet();
        if (wallet.isTransactionRelevant(tx)) {
            wallet.receivePending(tx, null);
            broadcastTransaction(tx);
        }
    }

    public void broadcastTransaction(@Nonnull final Transaction tx) {
        final Intent intent = new Intent(BlockchainService.ACTION_BROADCAST_TRANSACTION, null, applicationContext, BlockchainServiceImpl.class);
        intent.putExtra(BlockchainService.ACTION_BROADCAST_TRANSACTION_HASH, tx.getHash().getBytes());
        applicationContext.startService(intent);
    }

    public static PackageInfo packageInfoFromContext(final Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (final PackageManager.NameNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    public PackageInfo packageInfo() {
        return packageInfo;
    }

    public final String applicationPackageFlavor() {
        final String packageName = applicationContext.getPackageName();
        final int index = packageName.lastIndexOf('_');

        if (index != -1) {
            return packageName.substring(index + 1);
        } else {
            return null;
        }
    }

    public static String httpUserAgent(final String versionName) {
        final VersionMessage versionMessage = new VersionMessage(Constants.NETWORK_PARAMETERS, 0);
        versionMessage.appendToSubVer(Constants.USER_AGENT, versionName, null);
        return versionMessage.subVer;
    }

    public String httpUserAgent() {
        return httpUserAgent(packageInfo().versionName);
    }

    public int maxConnectedPeers() {
        final int memoryClass = activityManager.getMemoryClass();
        if (memoryClass <= Constants.MEMORY_CLASS_LOWEND) {
            return Constants.MAX_NUM_CONNECTED_PEERS_LOW;
        } else {
            return Constants.MAX_NUM_CONNECTED_PEERS_HIGH;
        }
    }

    public static void scheduleStartBlockchainService(@Nonnull final Context context) {
        final Configuration config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));
        final long lastUsedAgo = config.getLastUsedAgo();

        // apply some backoff
        final long alarmInterval;
        if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_JUST_MS)
            alarmInterval = AlarmManager.INTERVAL_FIFTEEN_MINUTES;
        else if (lastUsedAgo < Constants.LAST_USAGE_THRESHOLD_RECENTLY_MS)
            alarmInterval = AlarmManager.INTERVAL_HALF_DAY;
        else
            alarmInterval = AlarmManager.INTERVAL_DAY;

        log.info("last used {} minutes ago, rescheduling blockchain sync in roughly {} minutes", lastUsedAgo / DateUtils.MINUTE_IN_MILLIS,
                alarmInterval / DateUtils.MINUTE_IN_MILLIS);

        final AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        final PendingIntent alarmIntent = PendingIntent.getService(context, 0, new Intent(context, BlockchainServiceImpl.class), 0);
        alarmManager.cancel(alarmIntent);

        // workaround for no inexact set() before KitKat
        final long now = System.currentTimeMillis();
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, now + alarmInterval, AlarmManager.INTERVAL_DAY, alarmIntent);
    }
}
