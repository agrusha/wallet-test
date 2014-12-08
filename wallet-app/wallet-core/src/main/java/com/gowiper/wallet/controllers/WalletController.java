package com.gowiper.wallet.controllers;

import org.bitcoinj.core.Wallet;

import java.util.List;

public interface WalletController {
    public Wallet getWallet();
    public void saveWallet();
    public Wallet importWallet(List<String> mnemonicCode);
    public List<String> getMnemonicCode(String password) throws Throwable;
    public void replaceWallet(Wallet newWallet);
}
