package de.schildbach.wallet.ui;

import android.app.Application;
import android.os.StrictMode;
import com.gowiper.wallet.WalletClient;
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

        walletClient = WalletClient.getInstance(this);
    }
}
