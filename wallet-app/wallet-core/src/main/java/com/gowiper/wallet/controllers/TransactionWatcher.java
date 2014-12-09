package com.gowiper.wallet.controllers;

import android.content.SharedPreferences;
import android.text.format.DateUtils;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableSupport;
import com.gowiper.wallet.Configuration;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.util.ThrottlingWalletChangeListener;
import lombok.Delegate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.Wallet;

import java.util.*;

@Slf4j
public class TransactionWatcher implements Observable<TransactionWatcher> {
    public static enum Direction {
        RECEIVED, SENT
    }
    public static final long THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    @Delegate
    ObservableSupport<TransactionWatcher> observableSupport = new ObservableSupport<TransactionWatcher>(this);
    private final Wallet wallet;
    private final TransactionComparator transactionComparator = new TransactionComparator();

    public TransactionWatcher(WalletClient client) {
        this.wallet = client.getWallet();
        Configuration config = client.getConfiguration();
        config.registerOnSharedPreferenceChangeListener(new PreferencesListener());

        TransactionChangeListener transactionChangeListener = new TransactionChangeListener();
        wallet.addEventListener(transactionChangeListener);
        transactionChangeListener.onReorganize(null);
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

    private static class TransactionComparator implements Comparator<Transaction> {
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
}
