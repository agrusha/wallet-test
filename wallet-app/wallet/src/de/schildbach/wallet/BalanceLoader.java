package de.schildbach.wallet;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Wallet;

public class BalanceLoader extends AbstractLoader<Coin> {
    private final Wallet wallet;

    public BalanceLoader(Wallet wallet, ListeningScheduledExecutorService backgroundExecutor) {
        super(backgroundExecutor);
        this.wallet = wallet;
    }

    @Override
    protected Coin getData() {
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED);
    }
}
