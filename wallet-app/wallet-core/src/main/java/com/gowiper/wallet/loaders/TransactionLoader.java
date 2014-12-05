package com.gowiper.wallet.loaders;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.Wallet;

import java.util.*;

public class TransactionLoader extends AbstractLoader<List<Transaction>> {

    private final Wallet wallet;
    private final TransactionComparator transactionComparator = new TransactionComparator();

    public TransactionLoader(Wallet wallet, ListeningScheduledExecutorService backgroundExecutor) {
        super(backgroundExecutor);
        this.wallet = wallet;
    }

    @Override
    protected List<Transaction> getData() {
        final Set<Transaction> transactions = wallet.getTransactions(true);
        final List<Transaction> transactionsList = new ArrayList<Transaction>(transactions);
        Collections.sort(transactionsList, transactionComparator);

        return transactionsList;
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
