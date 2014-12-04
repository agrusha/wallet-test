package de.schildbach.wallet.wallet;

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
import lombok.Delegate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoadingController<Loader extends AbstractLoader<Type>, Type> implements
        Observable<LoadingController< Loader, Type>>{
    @Delegate
    private final ObservableSupport<? extends LoadingController< Loader, Type>> observableSupport =
            new ObservableSupport<LoadingController< Loader, Type>>(this);

    private final Loader loader;
    private Type loadedData;
    private ListenableFuture<Type> loadingData;
    private final LoadingReceiver loadingReceiver = new LoadingReceiver();

    public LoadingController(Context context, Loader loader, String broadcastAction) {
        this.loader = loader;

        if (broadcastAction != null) {
            LocalBroadcastManager broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
            broadcastManager.registerReceiver(new BroadcastListener(), new IntentFilter(broadcastAction));
        }
    }

    protected ListenableFuture<Type> loadData() {
        if (loadingData == null) {
            return load();
        } else if (loadingData.isDone()){
            if(loadedData == null) {
                return load();
            } else {
                return Futures.immediateFuture(loadedData);
            }
        } else {
            return loadingData;
        }
    }

    protected ListenableFuture<Type> load() {
        loadingData = loader.loadData();
        Futures.addCallback(loadingData, loadingReceiver);
        return loadingData;
    }

    private class LoadingReceiver implements FutureCallback<Type> {
        @Override
        public void onSuccess(Type result) {
            loadedData = result;
            notifyObservers();
        }

        @Override
        public void onFailure(Throwable t) {
            log.error("loading data failed ", t);
        }
    }

    private class BroadcastListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            load();
        }
    }
}
