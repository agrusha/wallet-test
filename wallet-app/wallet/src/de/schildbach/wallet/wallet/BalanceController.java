package de.schildbach.wallet.wallet;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableDelegate;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import lombok.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Wallet;

@Slf4j
public class BalanceController implements Observable<BalanceController>{
    @Delegate
    ObservableDelegate<BalanceController, LoadingController<BalanceLoader, Coin>> observableDelegate =
            new ObservableDelegate<BalanceController, LoadingController<BalanceLoader, Coin>>(this);

    private final LoadingController<BalanceLoader, Coin> loadingController;

    public BalanceController(WalletClient client) {
        Wallet wallet = client.getWallet();
        this.loadingController = new LoadingController<BalanceLoader, Coin>(
                client.getApplicationContext(),
                new BalanceLoader(wallet, client.getBackgroundExecutor()),
                BlockchainServiceController.ACTION_WALLET_CHANGED );
        this.loadingController.addObserver(observableDelegate);
        wallet.addEventListener(new WalletBalanceChangeListener());
        this.loadingController.load();
    }

    public ListenableFuture<Coin> loadBalance() {
        return loadingController.loadData();
    }

    private class WalletBalanceChangeListener extends ThrottlingWalletChangeListener {
        @Override
        public void onThrottledWalletChanged() {
            loadingController.load();
        }
    }
}
