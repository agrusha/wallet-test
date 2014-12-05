package com.gowiper.wallet;

import android.app.Application;
import android.os.StrictMode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WalletApplication extends Application {

    @Getter
    private WalletClient walletClient;

    @Override
    public void onCreate() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads().permitDiskWrites().penaltyLog().build());

        super.onCreate();

        walletClient = new WalletClient(this);
    }
}
