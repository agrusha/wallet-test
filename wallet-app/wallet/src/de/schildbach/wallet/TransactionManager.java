package de.schildbach.wallet;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import org.bitcoinj.core.Transaction;

import java.util.List;

public interface TransactionManager extends Observable<TransactionManager> {
    public ListenableFuture<List<Transaction>> loadTransactions(Direction direction);

    public static enum Direction {
        RECEIVED, SENT
    }
}
