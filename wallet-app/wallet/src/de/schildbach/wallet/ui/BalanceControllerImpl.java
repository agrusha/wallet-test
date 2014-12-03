package de.schildbach.wallet.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableSupport;
import de.schildbach.wallet.BlockchainServiceController;
import de.schildbach.wallet.WalletClient;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import lombok.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Wallet;

@Slf4j
public class BalanceControllerImpl implements Observable<BalanceControllerImpl> {
    @Delegate
    private final ObservableSupport<BalanceControllerImpl> observableSupport =
            new ObservableSupport<BalanceControllerImpl>(this);

    private final Wallet wallet;
    private Coin currentBalance;
    private ListenableFuture<Coin> loadingBalance;
    private final BalanceLoader balanceLoader;

    private final BalanceLoadingReceiver balanceLoadingReceiver = new BalanceLoadingReceiver();

    public BalanceControllerImpl(WalletClient client) {
        this.wallet = client.getWallet();
        this.balanceLoader = new BalanceLoader(wallet, client.getBackgroundExecutor());

        wallet.addEventListener(new WalletBalanceChangeListener());

        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(client.getApplicationContext());
        broadcastManager.registerReceiver(new WalletChangeReceiver(), new IntentFilter(BlockchainServiceController.ACTION_WALLET_CHANGED));
        load();
    }

    public ListenableFuture<Coin> loadBalance() {
        if(loadingBalance == null) {
            return load();
        } else if (loadingBalance.isDone()) {
            if(currentBalance == null) {
                return load();
            } else {
                return Futures.immediateFuture(currentBalance);
            }
        } else {
            return loadingBalance;
        }
    }

    private ListenableFuture<Coin> load() {
        loadingBalance = balanceLoader.loadBalance();
        Futures.addCallback(loadingBalance, balanceLoadingReceiver);
        return loadingBalance;
    }

    private class BalanceLoadingReceiver implements FutureCallback<Coin>{
        @Override
        public void onSuccess(Coin result) {
            currentBalance = result;
            notifyObservers();
        }

        @Override
        public void onFailure(Throwable t) {
            log.error("Failed to get current balance ", t);
        }
    }

    private class WalletBalanceChangeListener extends ThrottlingWalletChangeListener {
        @Override
        public void onThrottledWalletChanged() {
            load();
        }
    }

    private class WalletChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            load();
        }
    }
}
