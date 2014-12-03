package de.schildbach.wallet;

import android.content.*;
import android.support.v4.content.LocalBroadcastManager;
import android.text.format.DateUtils;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.ObservableSupport;
import de.schildbach.wallet.ui.TransactionsListFragment;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import lombok.Delegate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Wallet;

import java.util.List;

@Slf4j
public class TransactionManagerImpl implements TransactionManager {
    public static final long THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

    @Delegate
    private final ObservableSupport<TransactionManager> observableSupport =
            new ObservableSupport<TransactionManager>(this);

    private final Wallet wallet;
    private ListenableFuture<List<Transaction>> loadingTransactions;
    private List<Transaction> loadedTransactions;
    private final TransactionLoader transactionLoader;

    private final TransactionsLoadingReceiver transactionsLoadingReceiver = new TransactionsLoadingReceiver();

    public TransactionManagerImpl(WalletClient client) {
        Context context = client.getApplicationContext();
        this.wallet = client.getWallet();
        this.transactionLoader = new TransactionLoader(context, wallet);
        Configuration config = client.getConfiguration();
        LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(context);

        config.registerOnSharedPreferenceChangeListener(new PreferencesListener());
        broadcastManager.registerReceiver(new WalletChangeReceiver(), new IntentFilter(BlockchainServiceController.ACTION_WALLET_CHANGED));
        TransactionChangeListener transactionChangeListener = new TransactionChangeListener();
        wallet.addEventListener(transactionChangeListener);
        transactionChangeListener.onReorganize(null);
    }

    @Override
    public ListenableFuture<List<Transaction>> loadTransactions(final TransactionsListFragment.Direction direction) {
        if(loadingTransactions == null) {
            return getFilteredTransactions(load(), direction);
        } else if(loadingTransactions.isDone()) {
            if (loadedTransactions == null) {
                return getFilteredTransactions(load(), direction);
            } else {
                return getFilteredTransactions(Futures.immediateFuture(loadedTransactions), direction);
            }
        } else {
            return getFilteredTransactions(loadingTransactions, direction);
        }
    }

    private ListenableFuture<List<Transaction>> getFilteredTransactions(ListenableFuture<List<Transaction>> futureTransactions,
                                                                        final TransactionsListFragment.Direction direction) {
        return Futures.transform(futureTransactions, new Function<List<Transaction>, List<Transaction>>() {
            @Override
            public List<Transaction> apply(List<Transaction> input) {
                return applyTransactionsFilter(input, direction);
            }
        });
    }

    private  ListenableFuture<List<Transaction>> load() {
        loadingTransactions = transactionLoader.loadTransactions();
        Futures.addCallback(loadingTransactions, transactionsLoadingReceiver);
        return loadingTransactions;
    }

    private List<Transaction> applyTransactionsFilter(List<Transaction> origin, TransactionsListFragment.Direction direction) {
        Iterable<Transaction> filteredTransactions = Iterables.filter(origin, new TransactionsFilter(direction));
        return Lists.newArrayList(filteredTransactions);
    }

    private class TransactionsLoadingReceiver implements FutureCallback<List<Transaction>>{
        @Override
        public void onSuccess(List<Transaction> result) {
            loadedTransactions = result;
            notifyObservers();
        }

        @Override
        public void onFailure(Throwable t) {
            log.error("Failed to load transactions list ", t);
        }
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private class TransactionsFilter implements Predicate<Transaction> {
        private final TransactionsListFragment.Direction direction;
        @Override
        public boolean apply(Transaction input) {
            final boolean sent = input.getValue(wallet).signum() < 0;
            final boolean isInternal = input.getPurpose() == Transaction.Purpose.KEY_ROTATION;

            return direction == null || !isInternal && ((direction == TransactionsListFragment.Direction.SENT) == sent);
        }
    }

    private class TransactionChangeListener extends ThrottlingWalletChangeListener {
        public TransactionChangeListener() {
            super(THROTTLE_MS, true, true, false);
        }

        @Override
        public void onThrottledWalletChanged() {
            load();
        }
    }

    private class WalletChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            load();
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
