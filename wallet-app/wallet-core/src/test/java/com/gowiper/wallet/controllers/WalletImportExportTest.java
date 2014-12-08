package com.gowiper.wallet.controllers;


import com.google.common.base.Joiner;
import com.gowiper.wallet.Constants;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.crypto.EncryptedData;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.wallet.DeterministicSeed;
import org.junit.Assert;
import org.junit.Test;

import java.net.UnknownHostException;
import java.util.List;

public class WalletImportExportTest {

    @Test
    public void testImportWallet() throws BlockStoreException, UnknownHostException {
        log("=======");
        log("testImportWallet");
        Wallet wallet = createNewWallet();

        List<String> words = exportNonEncryptedWallet(wallet);

        // wallet import
        DeterministicSeed newSeed = new DeterministicSeed(words, null, null, Utils.currentTimeSeconds());
        Wallet newWallet = Wallet.fromSeed(Constants.NETWORK_PARAMETERS, newSeed);
        Assert.assertNotNull(newWallet);
        log("new wallet created by passwords");


        List<String>  checkWords = exportNonEncryptedWallet(newWallet);

        checkPassPhrases(words, checkWords);
    }

    @Test
    public void walletEncryptionCheck() {
        log("=======");
        log("walletEncryptionCheck");
        Wallet wallet = createNewWallet();

        List<String> originWords = exportNonEncryptedWallet(wallet);

        String password = "password";
        wallet = encryptWallet(wallet, password);


        EncryptedData encrypted = exportEncryptedWallet(wallet);

        try {
            wallet = decryptWallet(wallet, "wrong password");
        } catch (KeyCrypterException ex) {
            log("decrypt with wrong password failed");
        }

        wallet= decryptWallet(wallet, password);

        List<String> newWords =exportNonEncryptedWallet(wallet);
        checkPassPhrases(originWords, newWords);
    }

    private Wallet createNewWallet() {
        Wallet wallet = new Wallet(Constants.NETWORK_PARAMETERS);
        Assert.assertNotNull(wallet);
        log("new wallet created");
        return wallet;
    }

    private List<String> exportNonEncryptedWallet(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        Assert.assertNotNull(seed);
        Assert.assertFalse(seed.isEncrypted());

        List<String> words = seed.getMnemonicCode();
        Assert.assertNotNull(words);
        Assert.assertEquals(12, words.size());
        String passphrase = Joiner.on(' ').join(words);
        log("passphrase is: " + passphrase);

        return words;
    }

    private EncryptedData exportEncryptedWallet(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        Assert.assertNotNull(seed);
        Assert.assertTrue(seed.isEncrypted());
        // words should be null if encryption provided
        List<String> words = seed.getMnemonicCode();
        Assert.assertNull(words);

        EncryptedData encrypted = seed.getEncryptedData();
        Assert.assertNotNull(encrypted);
        log("encrypted data: " + encrypted.toString());
        return encrypted;
    }

    private Wallet encryptWallet(Wallet wallet, String password) {
        wallet.encrypt(password);
        DeterministicSeed seed = wallet.getKeyChainSeed();
        Assert.assertTrue( seed.isEncrypted());
        log("wallet encrypted");
        return wallet;
    }

    private Wallet decryptWallet(Wallet wallet, String password) {
        wallet.decrypt(password);
        DeterministicSeed seed = wallet.getKeyChainSeed();
        Assert.assertFalse(seed.isEncrypted());
        log("wallet decrypted");
        return wallet;
    }

    private void checkPassPhrases(List<String> originWords, List<String> newWords) {
        String passphrase = Joiner.on(' ').join(originWords);
        String checkPassphrase = Joiner.on(' ').join(newWords);
        Assert.assertEquals(passphrase, checkPassphrase);
        log("wallets pass phrases coincide");
    }

    private void log(String output) {
        System.out.println(output);
    }
}