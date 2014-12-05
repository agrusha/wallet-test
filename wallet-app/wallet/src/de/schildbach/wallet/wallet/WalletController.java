package de.schildbach.wallet.wallet;

import org.bitcoinj.core.Wallet;

public interface WalletController {
    public Wallet getWallet();
    public void saveWallet();
    public void replaceWallet(Wallet newWallet);
}