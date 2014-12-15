package com.gowiper.wallet.controllers;

import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoinj.core.Wallet;

import java.util.List;

public interface WalletController {
    public Wallet getWallet();
    public void saveWallet();
    public Wallet importWallet(List<String> mnemonicCode);
    public List<String> getMnemonicCode(String password) throws Throwable;
    public void replaceWallet(Wallet newWallet);
    public ListenableFuture<Void> setPinCode(String oldCode, String newCode);
}
