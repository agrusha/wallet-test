package com.gowiper.wallet.controllers;

import android.content.*;
import android.support.v4.content.LocalBroadcastManager;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableSupport;
import com.gowiper.wallet.Configuration;
import com.gowiper.wallet.util.ThrottlingWalletChangeListener;
import lombok.Delegate;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.Wallet;

import java.util.*;

public class WalletUpdateController implements Observable<WalletUpdateController> {
    @Delegate
    ObservableSupport<WalletUpdateController> observableSupport =
            new ObservableSupport<WalletUpdateController>(this);

    private final Wallet wallet;
    private final WalletUpdateController.TransactionComparator transactionComparator = new WalletUpdateController.TransactionComparator();

    public WalletUpdateController(Context context, Configuration configuration, Wallet wallet) {
        this.wallet = wallet;
        wallet.addEventListener(new WalletChangeListener());

        configuration.registerOnSharedPreferenceChangeListener(new PreferencesListener());

        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(context);
        broadcastManager.registerReceiver(new BroadcastListener(),
                new IntentFilter(BlockchainServiceController.ACTION_WALLET_CHANGED));
    }

    public Coin getBalance() {
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED);
    }

    public Coin getAvailableBalance() {
        return wallet.getBalance(Wallet.BalanceType.AVAILABLE);
    }

    public List<Transaction> getTransactions(final Direction direction) {
        return applyTransactionsFilter(getTransactionsList(), direction);
    }

    private List<Transaction> applyTransactionsFilter(List<Transaction> origin, Direction direction) {
        Iterable<Transaction> filteredTransactions = Iterables.filter(origin, new TransactionsFilter(direction));
        return Lists.newArrayList(filteredTransactions);
    }

    private List<Transaction> getTransactionsList() {
        final Set<Transaction> transactions = wallet.getTransactions(true);
        final List<Transaction> transactionsList = new ArrayList<Transaction>(transactions);
        Collections.sort(transactionsList, transactionComparator);

        return transactionsList;
    }

    public static enum Direction {
        RECEIVED, SENT
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private class TransactionsFilter implements Predicate<Transaction> {
        private final WalletUpdateController.Direction direction;
        @Override
        public boolean apply(Transaction input) {
            final boolean sent = input.getValue(wallet).signum() < 0;
            final boolean isInternal = input.getPurpose() == Transaction.Purpose.KEY_ROTATION;

            return direction == null || !isInternal && ((direction == WalletUpdateController.Direction.SENT) == sent);
        }
    }

    static class TransactionComparator implements Comparator<Transaction> {
        @Override
        public int compare(final Transaction tx1, final Transaction tx2) {
            final boolean pending1 = tx1.getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING;
            final boolean pending2 = tx2.getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING;

            if (pending1 != pending2) {
                return pending1 ? -1 : 1;
            }

            final Date updateTime1 = tx1.getUpdateTime();
            final long time1 = updateTime1 == null ? 0 : updateTime1.getTime();
            final Date updateTime2 = tx2.getUpdateTime();
            final long time2 = updateTime2 == null ? 0 : updateTime2.getTime();

            if (time1 != time2) {
                return time1 > time2 ? -1 : 1;
            }

            return tx1.getHash().compareTo(tx2.getHash());
        }
    }


    private class WalletChangeListener extends ThrottlingWalletChangeListener {
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

    private class PreferencesListener implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if(Configuration.PREFS_KEY_BTC_PRECISION.equals(key)) {
                notifyObservers();
            }
        }
    }

}
