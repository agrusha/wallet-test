package com.gowiper.wallet.controllers;

import android.content.Context;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableDelegate;
import com.gowiper.wallet.loaders.BlockchainStateLoader;
import com.gowiper.wallet.service.BlockchainService;
import com.gowiper.wallet.data.BlockchainState;
import lombok.Delegate;

public class BlockchainStateController implements Observable<BlockchainStateController>{
    @Delegate
    ObservableDelegate<BlockchainStateController, LoadingController<BlockchainStateLoader, BlockchainState>> observableDelegate =
            new ObservableDelegate<BlockchainStateController, LoadingController<BlockchainStateLoader, BlockchainState>>(this);

    private final LoadingController<BlockchainStateLoader, BlockchainState> loadingController;

    public BlockchainStateController(Context context, BlockchainService blockchainService,
                                     ListeningScheduledExecutorService backgroundExecutor) {
        loadingController = new LoadingController<BlockchainStateLoader, BlockchainState>(context,
              new BlockchainStateLoader(blockchainService, backgroundExecutor),
              BlockchainService.ACTION_BLOCKCHAIN_STATE);

        loadingController.addObserver(observableDelegate);
    }

    public ListenableFuture<BlockchainState> loadBlockchainState() {
        return loadingController.loadData();
    }
}
