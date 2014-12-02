package de.schildbach.wallet;

public interface BlockchainServiceController {
    public static final String ACTION_WALLET_CHANGED = WalletApplication.class.getPackage().getName() + ".wallet_changed";
    public void startBlockchainService(boolean cancelCoinsReceived);
    public void stopBlockchainService();
    public void resetBlockchainService();
}
