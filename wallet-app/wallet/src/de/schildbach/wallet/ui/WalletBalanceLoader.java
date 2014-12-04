/*
 * Copyright 2013-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui;

import android.content.*;
import android.support.v4.content.LocalBroadcastManager;
import de.schildbach.wallet.wallet.BlockchainServiceController;
import de.schildbach.wallet.util.ThrottlingWalletChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.core.Wallet.BalanceType;
import org.bitcoinj.utils.Threading;

import javax.annotation.Nonnull;
import java.util.concurrent.RejectedExecutionException;

/**
 * @author Andreas Schildbach
 */

@Slf4j
public final class WalletBalanceLoader extends AsyncTaskLoader<Coin> {
    private LocalBroadcastManager broadcastManager;
    private final Wallet wallet;

    public WalletBalanceLoader(final Context context, @Nonnull final Wallet wallet) {
        super(context);

        this.broadcastManager = LocalBroadcastManager.getInstance(context.getApplicationContext());
        this.wallet = wallet;
    }

    @Override
    protected void onStartLoading() {
        super.onStartLoading();

        wallet.addEventListener(walletChangeListener, Threading.SAME_THREAD);
        broadcastManager.registerReceiver(walletChangeReceiver, new IntentFilter(BlockchainServiceController.ACTION_WALLET_CHANGED));

        safeForceLoad();
    }

    @Override
    protected void onStopLoading() {
        broadcastManager.unregisterReceiver(walletChangeReceiver);
        wallet.removeEventListener(walletChangeListener);
        walletChangeListener.removeCallbacks();

        super.onStopLoading();
    }

    @Override
    protected void onReset() {
        broadcastManager.unregisterReceiver(walletChangeReceiver);
        wallet.removeEventListener(walletChangeListener);
        walletChangeListener.removeCallbacks();

        super.onReset();
    }

    @Override
    public Coin loadInBackground() {
        return wallet.getBalance(BalanceType.ESTIMATED);
    }

    private final ThrottlingWalletChangeListener walletChangeListener = new ThrottlingWalletChangeListener() {
        @Override
        public void onThrottledWalletChanged() {
            safeForceLoad();
        }
    };

    private final BroadcastReceiver walletChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            safeForceLoad();
        }
    };

    private void safeForceLoad() {
        try {
            forceLoad();
        } catch (final RejectedExecutionException x) {
            log.info("rejected execution: " + WalletBalanceLoader.this.toString());
        }
    }
}
