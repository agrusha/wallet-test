package de.schildbach.wallet;

public interface BlockchainServiceController {
    public void startBlockchainService(boolean cancelCoinsReceived);

    public void stopBlockchainService();

    public void resetBlockchainService();
}
