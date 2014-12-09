package com.gowiper.wallet.controllers;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.wallet.data.ExchangeRate;
import com.gowiper.wallet.service.BlockchainState;

import java.util.Map;

// aggregates balance manager, blockchain state manager and exchange currency manager
public interface BlockchainManager extends Observable<BlockchainManager>{
    public ListenableFuture<BlockchainState> loadBlockchainState();
    public ListenableFuture<Map<String, ExchangeRate>> loadExchangeRate();

    public BlockchainStateController getBlockchainStateController();
    public ExchangeRatesController getExchangeRatesController();
}
