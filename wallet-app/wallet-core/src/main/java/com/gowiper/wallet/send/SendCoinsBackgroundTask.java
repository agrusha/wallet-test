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
            return wallet.sendCoinsOffline(sendRequest);
        }
    }
}
