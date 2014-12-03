package de.schildbach.wallet.ui;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Wallet;

import java.util.concurrent.Callable;

public class BalanceLoader {
    private final Wallet wallet;
    private final ListeningScheduledExecutorService backgroundExecutor;

    private final LoadBalanceTask loadBalanceTask = new LoadBalanceTask();

    public BalanceLoader(Wallet wallet, ListeningScheduledExecutorService backgroundExecutor) {
        this.wallet = wallet;
        this.backgroundExecutor = backgroundExecutor;
    }

    public ListenableFuture<Coin> loadBalance(){
        return backgroundExecutor.submit(loadBalanceTask);
    }

    private Coin getBalance() {
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED);
    }

    private class LoadBalanceTask implements Callable<Coin>{
        @Override
        public Coin call() throws Exception {
            return getBalance();
        }
    }
}
