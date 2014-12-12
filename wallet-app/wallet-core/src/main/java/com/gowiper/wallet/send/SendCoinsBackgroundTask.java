package com.gowiper.wallet.send;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Wallet;

import javax.annotation.Nonnull;
import java.util.concurrent.Callable;

@RequiredArgsConstructor(suppressConstructorProperties = true)
public class SendCoinsBackgroundTask {
    private final Wallet wallet;
    private final ListeningScheduledExecutorService backgroundExecutor;

    public ListenableFuture<Transaction> sendCoinsAsync(@Nonnull final Wallet.SendRequest sendRequest) {
        return backgroundExecutor.submit(new SendCoinsTask(sendRequest));
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private class SendCoinsTask implements Callable<Transaction> {
        private final Wallet.SendRequest sendRequest;

        @Override
        public Transaction call() throws Exception {
            // NOTE: sorts of Exceptions that are good to handle:
            // - InsufficientMoneyException - user tries to send more coins that he has
            // - KeyCrypterException - user does not provided correct key to decrypt his wallet
            // - CouldNotAdjustDownwards - "empty wallet problem" - user's funds are enough to
            //              make the payment but not enough to charge the fee
            // - CompletionException - payment transaction failed due to other reasons
            return wallet.sendCoinsOffline(sendRequest);
        }
    }
}
