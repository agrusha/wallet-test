package de.schildbach.wallet;

import android.content.Context;
import android.content.Intent;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainServiceImpl;

public class BlockchainServiceControllerImpl implements BlockchainServiceController {
    private final Context applicationContext;
    private final Intent blockchainServiceIntent;
    private final Intent blockchainServiceCancelCoinsReceivedIntent;
    private final Intent blockchainServiceResetBlockchainIntent;

    public BlockchainServiceControllerImpl(Context context) {
        this.applicationContext = context;
        this.blockchainServiceIntent = new Intent(context, BlockchainServiceImpl.class);
        this.blockchainServiceCancelCoinsReceivedIntent = new Intent(BlockchainService.ACTION_CANCEL_COINS_RECEIVED,
                                                                     null, context, BlockchainServiceImpl.class);
        this.blockchainServiceResetBlockchainIntent = new Intent(BlockchainService.ACTION_RESET_BLOCKCHAIN, null,
                                                                 context, BlockchainService.class);
    }

    @Override
    public void startBlockchainService(boolean cancelCoinsReceived) {
        if (cancelCoinsReceived) {
            applicationContext.startService(blockchainServiceCancelCoinsReceivedIntent);
        } else {
            applicationContext.startService(blockchainServiceIntent);
        }
    }

    @Override
    public void stopBlockchainService() {
        applicationContext.stopService(blockchainServiceIntent);
    }

    @Override
    public void resetBlockchainService() {
        applicationContext.startService(blockchainServiceResetBlockchainIntent);
    }
}
