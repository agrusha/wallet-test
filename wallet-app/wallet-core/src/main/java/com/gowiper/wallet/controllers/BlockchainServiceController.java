package com.gowiper.wallet.controllers;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.wallet.WalletApplication;
import com.gowiper.wallet.service.BlockchainState;

public interface BlockchainServiceController extends Observable<BlockchainServiceController> {
    public static final String ACTION_WALLET_CHANGED = WalletApplication.class.getPackage().getName() + ".wallet_changed";
    public void startBlockchainService(boolean cancelCoinsReceived);
    public void stopBlockchainService();
    public void resetBlockchainService();
    public ListenableFuture<BlockchainState> loadBlockchainState();
}
