package com.gowiper.wallet.controllers;

import android.content.SharedPreferences;
import android.text.format.DateUtils;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableDelegate;
import com.gowiper.wallet.Configuration;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.loaders.TransactionLoader;
import com.gowiper.wallet.util.ThrottlingWalletChangeListener;
import lombok.Delegate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Wallet;

import java.util.List;

@Slf4j
public class TransactionController implements Observable<TransactionController> {
    public static enum Direction {
        RECEIVED, SENT
    }

    public static final long THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    @Delegate
    ObservableDelegate<TransactionController, LoadingController<TransactionLoader, List<Transaction>>> observableDelegate = new ObservableDelegate<TransactionController, LoadingController<TransactionLoader, List<Transaction>>>(this);

    private final LoadingController<TransactionLoader, List<Transaction>> loadingController;

    private final Wallet wallet;

    public TransactionController(WalletClient client) {
        this.wallet = client.getWallet();
        this.loadingController = new LoadingController<TransactionLoader, List<Transaction>>(client.getApplicationContext(),
                new TransactionLoader(wallet, client.getBackgroundExecutor()),
                BlockchainServiceController.ACTION_WALLET_CHANGED);
        this.loadingController.addObserver(observableDelegate);
        Configuration config = client.getConfiguration();
        config.registerOnSharedPreferenceChangeListener(new PreferencesListener());

        TransactionChangeListener transactionChangeListener = new TransactionChangeListener();
        wallet.addEventListener(transactionChangeListener);
        transactionChangeListener.onReorganize(null);
    }

    public ListenableFuture<List<Transaction>> loadTransactions(final Direction direction) {
        return getFilteredTransactions(loadingController.loadData(), direction);
    }

    private ListenableFuture<List<Transaction>> getFilteredTransactions(
            ListenableFuture<List<Transaction>> futureTransactions,
            final Direction direction) {
        return Futures.transform(futureTransactions, new Function<List<Transaction>, List<Transaction>>() {
            @Override
            public List<Transaction> apply(List<Transaction> input) {
                return applyTransactionsFilter(input, direction);
            }
        });
    }

    private List<Transaction> applyTransactionsFilter(List<Transaction> origin, Direction direction) {
        Iterable<Transaction> filteredTransactions = Iterables.filter(origin, new TransactionsFilter(direction));
        return Lists.newArrayList(filteredTransactions);
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private class TransactionsFilter implements Predicate<Transaction> {
        private final Direction direction;
        @Override
        public boolean apply(Transaction input) {
            final boolean sent = input.getValue(wallet).signum() < 0;
            final boolean isInternal = input.getPurpose() == Transaction.Purpose.KEY_ROTATION;

            return direction == null || !isInternal && ((direction == Direction.SENT) == sent);
        }
    }

    private class TransactionChangeListener extends ThrottlingWalletChangeListener {
        public TransactionChangeListener() {
            super(THROTTLE_MS, true, true, false);
        }

        @Override
        public void onThrottledWalletChanged() {
            loadingController.load();
        }
    }

    private class PreferencesListener implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if(Configuration.PREFS_KEY_BTC_PRECISION.equals(key)) {
                notifyObservers();
            }
        }
    }
}
