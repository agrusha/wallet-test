package com.gowiper.wallet.controllers;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableDelegate;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.loaders.BlockchainStateLoader;
import com.gowiper.wallet.service.BlockchainService;
import com.gowiper.wallet.service.BlockchainState;
import lombok.Delegate;

public class BlockchainStateController implements Observable<BlockchainStateController>{
    @Delegate
    ObservableDelegate<BlockchainStateController, LoadingController<BlockchainStateLoader, BlockchainState>> observableDelegate =
            new ObservableDelegate<BlockchainStateController, LoadingController<BlockchainStateLoader, BlockchainState>>(this);

    private final LoadingController<BlockchainStateLoader, BlockchainState> loadingController;

    public BlockchainStateController(WalletClient client) {
        loadingController = new LoadingController<BlockchainStateLoader, BlockchainState>(client.getApplicationContext(),
              new BlockchainStateLoader(client.getBlockchainService(), client.getBackgroundExecutor()),
              BlockchainService.ACTION_BLOCKCHAIN_STATE);
    }

    public ListenableFuture<BlockchainState> loadBlockchainState() {
        return loadingController.loadData();
    }
}
