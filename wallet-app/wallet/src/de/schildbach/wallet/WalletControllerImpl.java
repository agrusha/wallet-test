package de.schildbach.wallet;

import android.content.Context;
import android.text.format.DateUtils;
import de.schildbach.wallet.util.Io;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.store.UnreadableWalletException;
import org.bitcoinj.store.WalletProtobufSerializer;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.WalletFiles;

import javax.annotation.Nonnull;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Slf4j
public class WalletControllerImpl implements WalletController {
    private final File walletFile;
    private final Configuration configuration;
    private final Context context;

    @Getter private Wallet wallet;

    public WalletControllerImpl(Context context, Configuration configuration) {
        this.configuration = configuration;
        this.context = context;
        this.walletFile = context.getFileStreamPath(Constants.Files.WALLET_FILENAME_PROTOBUF);
        loadWalletFromProtobuf();
        afterLoadWallet();
    }

    private void loadWalletFromProtobuf(){
        if (walletFile.exists()) {
            final long start = System.currentTimeMillis();

            FileInputStream walletStream = null;

            try {
                walletStream = new FileInputStream(walletFile);

                wallet = new WalletProtobufSerializer().readWallet(walletStream);

                if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS)) {
                    throw new UnreadableWalletException("bad wallet network parameters: " + wallet.getParams().getId());
                }

                log.info("wallet loaded from: '" + walletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
            } catch (final FileNotFoundException x) {
                log.error("problem loading wallet", x);
                wallet = restoreWalletFromBackup();
            } catch (final UnreadableWalletException x) {
                log.error("problem loading wallet", x);
                wallet = restoreWalletFromBackup();
            } finally {
                if (walletStream != null) {
                    try {
                        walletStream.close();
                    } catch (final IOException x) {
                        // swallow
                    }
                }
            }

            if (!wallet.isConsistent()) {
                wallet = restoreWalletFromBackup();
            }

            if (!wallet.getParams().equals(Constants.NETWORK_PARAMETERS)) {
                throw new Error("bad wallet network parameters: " + wallet.getParams().getId());
            }
        } else {
            wallet = new Wallet(Constants.NETWORK_PARAMETERS);

            backupWallet();

            configuration.armBackupReminder();

            log.info("new wallet created");
        }
    }

    private Wallet restoreWalletFromBackup() {
        InputStream inputStream = null;

        try {
            inputStream = context.openFileInput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF);

            final Wallet wallet = new WalletProtobufSerializer().readWallet(inputStream);

            if (!wallet.isConsistent()) {
                throw new Error("inconsistent backup");
            }

//            application.resetBlockchain();

            log.info("wallet restored from backup: '" + Constants.Files.WALLET_KEY_BACKUP_PROTOBUF + "'");

            return wallet;
        } catch (final IOException x) {
            throw new Error("cannot read backup", x);
        } catch (final UnreadableWalletException x) {
            throw new Error("cannot read backup", x);
        } finally {
           closeStream(inputStream);
        }
    }

    private void backupWallet() {
        final Protos.Wallet.Builder builder = new WalletProtobufSerializer().walletToProto(wallet).toBuilder();

        // strip redundant
        builder.clearTransaction();
        builder.clearLastSeenBlockHash();
        builder.setLastSeenBlockHeight(-1);
        builder.clearLastSeenBlockTimeSecs();
        final Protos.Wallet walletProto = builder.build();

        OutputStream outputStream = null;

        try {
            outputStream = context.openFileOutput(Constants.Files.WALLET_KEY_BACKUP_PROTOBUF, Context.MODE_PRIVATE);
            walletProto.writeTo(outputStream);
        } catch (final IOException x) {
            log.error("problem writing key backup", x);
        } finally {
            closeStream(outputStream);
        }

        try {
            final String filename = String.format(Locale.US, "%s.%02d", Constants.Files.WALLET_KEY_BACKUP_PROTOBUF,
                    (System.currentTimeMillis() / DateUtils.DAY_IN_MILLIS) % 100l);
            outputStream = context.openFileOutput(filename, Context.MODE_PRIVATE);
            walletProto.writeTo(outputStream);
        } catch (final IOException x) {
            log.error("problem writing key backup", x);
        } finally {
            closeStream(outputStream);
        }
    }

    private void closeStream(Closeable stream) {
        try {
            stream.close();
        } catch (IOException e) {
            log.warn("Exception while closing stream", e);
        }
    }

    @Override
    public void saveWallet() {
        try {
            protobufSerializeWallet(wallet);
        } catch (final IOException x) {
            throw new RuntimeException(x);
        }
    }

    private void protobufSerializeWallet(@Nonnull final Wallet wallet) throws IOException {
        final long start = System.currentTimeMillis();

        wallet.saveToFile(walletFile);
        allowWalletFileAccess();

        log.debug("wallet saved to: '" + walletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
    }

    private void allowWalletFileAccess() {
        // make wallets world accessible in test mode
        if (Constants.TEST) {
            Io.chmod(walletFile, 0777);
        }

    }


    @Override
    public void replaceWallet(Wallet newWallet) {
        wallet.shutdownAutosaveAndWait();

        wallet = newWallet;
        configuration.maybeIncrementBestChainHeightEver(newWallet.getLastBlockSeenHeight());
        afterLoadWallet();
    }

    private void afterLoadWallet() {
        wallet.autosaveToFile(walletFile, 10, TimeUnit.SECONDS, new WalletAutosaveEventListener());

        // clean up spam
        wallet.cleanup();
    }

    private final class WalletAutosaveEventListener implements WalletFiles.Listener {
        @Override
        public void onBeforeAutoSave(final File file) {}

        @Override
        public void onAfterAutoSave(final File file) {
            allowWalletFileAccess();
        }
    }
}
