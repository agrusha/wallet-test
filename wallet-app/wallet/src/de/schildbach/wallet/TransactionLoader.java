package de.schildbach.wallet;

import android.content.Context;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.gowiper.utils.SimpleThreadFactory;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.Wallet;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class TransactionLoader {

    private final Wallet wallet;
    private final ListeningScheduledExecutorService backgroundExecutor;
    private final LoadTransactionsTask loadTransactionsTask = new LoadTransactionsTask();
    private final TransactionComparator transactionComparator = new TransactionComparator();

    public TransactionLoader(Context context, Wallet wallet) {
        this.wallet = wallet;
        this.backgroundExecutor = MoreExecutors.listeningDecorator(
                Executors.newScheduledThreadPool(2, SimpleThreadFactory.create("Scheduler", true))
        );
    }

    private List<Transaction> getTransactions() {
        final Set<Transaction> transactions = wallet.getTransactions(true);
        final List<Transaction> transactionsList = new ArrayList<Transaction>(transactions);
        Collections.sort(transactionsList, transactionComparator);

        return transactionsList;
    }

    public ListenableFuture<List<Transaction>> loadTransactions() {
        return backgroundExecutor.submit(loadTransactionsTask);
    }

    private class LoadTransactionsTask implements Callable<List<Transaction>>{
        @Override
        public List<Transaction> call() throws Exception {
            return getTransactions();
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
