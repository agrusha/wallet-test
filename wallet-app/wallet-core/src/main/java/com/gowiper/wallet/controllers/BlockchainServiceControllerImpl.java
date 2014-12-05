package com.gowiper.wallet.controllers;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import com.gowiper.wallet.service.BlockchainService;
import com.gowiper.wallet.service.BlockchainServiceImpl;
import lombok.Getter;

public class BlockchainServiceControllerImpl implements BlockchainServiceController {
    private final Context applicationContext;
    private final Intent blockchainServiceIntent;
    private final Intent blockchainServiceCancelCoinsReceivedIntent;
    private final Intent blockchainServiceResetBlockchainIntent;

    @Getter private BlockchainService blockchainService;
    private final BlockchainServiceConnection serviceConnection = new BlockchainServiceConnection();

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

        applicationContext.bindService(new Intent(applicationContext, BlockchainServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void stopBlockchainService() {
        applicationContext.stopService(blockchainServiceIntent);
    }

    @Override
    public void resetBlockchainService() {
        applicationContext.startService(blockchainServiceResetBlockchainIntent);

        final Intent broadcast = new Intent(ACTION_WALLET_CHANGED);
        broadcast.setPackage(applicationContext.getPackageName());
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(broadcast);
    }

    private class BlockchainServiceConnection implements ServiceConnection{
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            blockchainService = ((BlockchainServiceImpl.LocalBinder) service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            blockchainService = null;
        }
    }
}
