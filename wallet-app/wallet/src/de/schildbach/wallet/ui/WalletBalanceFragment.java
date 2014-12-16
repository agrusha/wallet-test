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

package de.schildbach.wallet.ui;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.gowiper.utils.observers.Observer;
import com.gowiper.wallet.Configuration;
import com.gowiper.wallet.Constants;
import com.gowiper.wallet.controllers.BalanceWatcher;
import com.gowiper.wallet.controllers.BlockchainServiceController;
import com.gowiper.wallet.controllers.ExchangeRatesController;
import com.gowiper.wallet.data.ExchangeRate;
import com.gowiper.wallet.WalletApplication;
import com.gowiper.wallet.service.BlockchainState;
import com.gowiper.wallet.util.GuiThreadExecutor;
import com.gowiper.wallet.WalletClient;
import de.schildbach.wallet_test.R;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.Fiat;

import javax.annotation.CheckForNull;
import java.util.Map;

/**
 * @author Andreas Schildbach
 */

@Slf4j
public final class WalletBalanceFragment extends Fragment implements Observer<BlockchainServiceController> {
    private WalletClient walletClient;
    private Configuration config;

    private BlockchainServiceController blockchainServiceController;
    private ExchangeRatesController exchangeRatesController;
    private BalanceWatcher balanceWatcher;
    private GuiThreadExecutor guiThreadExecutor;
    private final UpdateViewTask updateViewTask = new UpdateViewTask();
    private final BalanceUpdate balanceUpdate = new BalanceUpdate();
    private final BlockchainStateUpdate blockchainStateUpdate = new BlockchainStateUpdate();
    private final ExchangeRateUpdate exchangeRateUpdate = new ExchangeRateUpdate();

    private View viewBalance;
    private CurrencyTextView viewBalanceBtc;
    private View viewBalanceTooMuch;
    private FrameLayout viewBalanceLocalFrame;
    private CurrencyTextView viewBalanceLocal;
    private TextView viewProgress;

    private boolean showLocalBalance;

    @CheckForNull
    private Coin balance = null;
    @CheckForNull
    private ExchangeRate exchangeRate = null;
    @CheckForNull
    private BlockchainState blockchainState = null;

    private static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.walletClient = ((WalletApplication) activity.getApplication()).getWalletClient();
        this.config = walletClient.getConfiguration();
        this.blockchainServiceController = walletClient.getBlockchainServiceController();
        this.exchangeRatesController = walletClient.getExchangeRatesController();
        this.balanceWatcher = walletClient.getBalanceWatcher();
        this.guiThreadExecutor = walletClient.getGuiThreadExecutor();

        balance = balanceWatcher.getBalance();
        showLocalBalance = getResources().getBoolean(R.bool.show_local_balance);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.wallet_balance_fragment, container, false);
    }

    @Override
    public void onViewCreated(final View view, final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final boolean showExchangeRatesOption = getResources().getBoolean(R.bool.show_exchange_rates_option);

        viewBalance = view.findViewById(R.id.wallet_balance);
        if (showExchangeRatesOption) {
            viewBalance.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(final View v) {
                    startActivity(new Intent(getActivity(), ExchangeRatesActivity.class));
                }
            });
        } else {
            viewBalance.setEnabled(false);
        }

        viewBalanceBtc = (CurrencyTextView) view.findViewById(R.id.wallet_balance_btc);

        viewBalanceTooMuch = view.findViewById(R.id.wallet_balance_too_much);

        viewBalanceLocalFrame = (FrameLayout) view.findViewById(R.id.wallet_balance_local_frame);
        if (showExchangeRatesOption)
            viewBalanceLocalFrame.setForeground(getResources().getDrawable(R.drawable.dropdown_ic_arrow_small));

        viewBalanceLocal = (CurrencyTextView) view.findViewById(R.id.wallet_balance_local);
        viewBalanceLocal.setInsignificantRelativeSize(1);
        viewBalanceLocal.setStrikeThru(Constants.TEST);

        viewProgress = (TextView) view.findViewById(R.id.wallet_balance_progress);
    }

    @Override
    public void onResume() {
        super.onResume();

        blockchainServiceController.addObserver(this);
        balanceWatcher.addObserver(balanceUpdate);
        updateData();
        updateView();
    }

    @Override
    public void onPause() {
        blockchainServiceController.removeObserver(this);
        balanceWatcher.removeObserver(balanceUpdate);
        super.onPause();
    }

    private void updateView() {
        if (!isAdded()) {
            return;
        }

        final boolean showProgress;

        if (blockchainState != null && blockchainState.bestChainDate != null) {
            final long blockchainLag = System.currentTimeMillis() - blockchainState.bestChainDate.getTime();
            final boolean blockchainUptodate = blockchainLag < BLOCKCHAIN_UPTODATE_THRESHOLD_MS;
            final boolean noImpediments = blockchainState.impediments.isEmpty();

            showProgress = !(blockchainUptodate || !blockchainState.replaying);

            final String downloading = getString(noImpediments ? R.string.blockchain_state_progress_downloading
                    : R.string.blockchain_state_progress_stalled);

            if (blockchainLag < 2 * DateUtils.DAY_IN_MILLIS) {
                final long hours = blockchainLag / DateUtils.HOUR_IN_MILLIS;
                viewProgress.setText(getString(R.string.blockchain_state_progress_hours, downloading, hours));
            } else if (blockchainLag < 2 * DateUtils.WEEK_IN_MILLIS) {
                final long days = blockchainLag / DateUtils.DAY_IN_MILLIS;
                viewProgress.setText(getString(R.string.blockchain_state_progress_days, downloading, days));
            } else if (blockchainLag < 90 * DateUtils.DAY_IN_MILLIS) {
                final long weeks = blockchainLag / DateUtils.WEEK_IN_MILLIS;
                viewProgress.setText(getString(R.string.blockchain_state_progress_weeks, downloading, weeks));
            } else {
                final long months = blockchainLag / (30 * DateUtils.DAY_IN_MILLIS);
                viewProgress.setText(getString(R.string.blockchain_state_progress_months, downloading, months));
            }
        } else {
            showProgress = false;
        }

        if (!showProgress) {
            viewBalance.setVisibility(View.VISIBLE);

            if (!showLocalBalance)
                viewBalanceLocalFrame.setVisibility(View.GONE);

            if (balance != null) {
                viewBalanceBtc.setVisibility(View.VISIBLE);
                viewBalanceBtc.setFormat(config.getFormat());
                viewBalanceBtc.setAmount(balance);

                final boolean tooMuch = balance.isGreaterThan(Coin.COIN);

                viewBalanceTooMuch.setVisibility(tooMuch ? View.VISIBLE : View.GONE);

                if (showLocalBalance) {
                    if (exchangeRate != null) {
                        final Fiat localValue = exchangeRate.rate.coinToFiat(balance);
                        viewBalanceLocalFrame.setVisibility(View.VISIBLE);
                        viewBalanceLocal.setFormat(Constants.LOCAL_FORMAT.code(0, Constants.PREFIX_ALMOST_EQUAL_TO + exchangeRate.getCurrencyCode()));
                        viewBalanceLocal.setAmount(localValue);
                        viewBalanceLocal.setTextColor(getResources().getColor(R.color.fg_less_significant));
                    } else {
                        viewBalanceLocalFrame.setVisibility(View.INVISIBLE);
                    }
                }
            } else {
                viewBalanceBtc.setVisibility(View.INVISIBLE);
            }

            viewProgress.setVisibility(View.GONE);
        } else {
            viewProgress.setVisibility(View.VISIBLE);
            viewBalance.setVisibility(View.INVISIBLE);
        }
    }

    private void updateData() {
        Futures.addCallback(blockchainServiceController.loadBlockchainState(), blockchainStateUpdate, guiThreadExecutor);
        Futures.addCallback(exchangeRatesController.loadExchangeRate(), exchangeRateUpdate, guiThreadExecutor);
    }

    @Override
    public void handleUpdate(BlockchainServiceController updatedObject) {
        updateData();
    }

    private class UpdateViewTask implements Runnable {
        @Override
        public void run() {
            updateView();
        }
    }

    private class BalanceUpdate implements Observer<BalanceWatcher> {
        @Override
        public void handleUpdate(BalanceWatcher updatedObject) {
            balance = balanceWatcher.getBalance();
            guiThreadExecutor.execute(updateViewTask);
        }
    }

    private class BlockchainStateUpdate implements FutureCallback<BlockchainState> {
        @Override
        public void onSuccess(BlockchainState result) {
            blockchainState = result;
            guiThreadExecutor.execute(updateViewTask);
        }

        @Override
        public void onFailure(Throwable t) {
            log.error("Failed to get blockchain update ", t);
        }
    }

    private class ExchangeRateUpdate implements FutureCallback<Map<String, ExchangeRate>> {
        @Override
        public void onSuccess(Map<String, ExchangeRate> result) {
            final Map<String, ExchangeRate> ratesMap = result;
            String currencyCode = walletClient.getConfiguration().getExchangeCurrencyCode();
            if(ratesMap.containsKey(currencyCode)) {
                exchangeRate = ratesMap.get(currencyCode);
                guiThreadExecutor.execute(updateViewTask);
            } else {
                log.warn("Failed to get exchange rate for {} ", currencyCode);
            }
        }

        @Override
        public void onFailure(Throwable t) {
            log.error("Failed to get exchange rate update ", t);
        }
    }
}
