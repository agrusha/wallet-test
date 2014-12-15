package com.gowiper.wallet.controllers;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.ObservableDelegate;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.data.ExchangeRate;
import com.gowiper.wallet.service.BlockchainState;
import lombok.Delegate;
import lombok.Getter;

import java.util.Map;

public class BlockchainManagerImpl implements BlockchainManager{
    @Delegate
    ObservableDelegate<BlockchainManager, Object> observableDelegate =
            new ObservableDelegate<BlockchainManager, Object>(this);

    @Getter
    private final BlockchainStateController blockchainStateController;
    @Getter
    private final ExchangeRatesController exchangeRatesController;

    public BlockchainManagerImpl(WalletClient client) {
        this.blockchainStateController = new BlockchainStateController(client);
        this.exchangeRatesController = new ExchangeRatesController(client);

        blockchainStateController.addObserver(observableDelegate);
        exchangeRatesController.addObserver(observableDelegate);
    }

    @Override
    public ListenableFuture<BlockchainState> loadBlockchainState() {
        return blockchainStateController.loadBlockchainState();
    }

    @Override
    public ListenableFuture<Map<String, ExchangeRate>> loadExchangeRate() {
        return exchangeRatesController.loadExchangeRate();
    }
}
