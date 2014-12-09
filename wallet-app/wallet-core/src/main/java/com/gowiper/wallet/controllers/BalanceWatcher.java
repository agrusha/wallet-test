package com.gowiper.wallet.controllers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableSupport;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.util.ThrottlingWalletChangeListener;
import lombok.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Wallet;

@Slf4j
public class BalanceWatcher implements Observable<BalanceWatcher>{
    @Delegate
    ObservableSupport<BalanceWatcher> observableSupport = new ObservableSupport<BalanceWatcher>(this);
    private final Wallet wallet;

    public BalanceWatcher(WalletClient client) {
        this.wallet = client.getWallet();
        wallet.addEventListener(new WalletBalanceChangeListener());

        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(client.getApplicationContext());
        broadcastManager.registerReceiver(new BroadcastListener(),
                new IntentFilter(BlockchainServiceController.ACTION_WALLET_CHANGED));
    }

    public Coin getBalance() {
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED);
    }

    private class WalletBalanceChangeListener extends ThrottlingWalletChangeListener {
        @Override
        public void onThrottledWalletChanged() {
            notifyObservers();
        }
    }

    private class BroadcastListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            notifyObservers();
        }
    }
}
