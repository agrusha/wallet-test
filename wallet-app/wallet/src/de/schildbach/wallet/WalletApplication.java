/*
 * Copyright 2011-2014 the original author or authors.
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

package de.schildbach.wallet;

import android.app.Application;
import android.os.StrictMode;
import de.schildbach.wallet.wallet.WalletClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Andreas Schildbach
 */
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
