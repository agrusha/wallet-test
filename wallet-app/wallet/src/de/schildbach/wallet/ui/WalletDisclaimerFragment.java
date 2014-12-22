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

import android.app.Activity;
import android.app.Fragment;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.gowiper.utils.observers.Observer;
import com.gowiper.wallet.Configuration;
import com.gowiper.wallet.controllers.BlockchainServiceController;
import com.gowiper.wallet.data.BlockchainState;
import com.gowiper.wallet.data.BlockchainState.Impediment;
import de.schildbach.wallet_test.R;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.CheckForNull;
import java.util.Set;

/**
 * @author Andreas Schildbach
 */

@Slf4j
public final class WalletDisclaimerFragment extends Fragment implements OnSharedPreferenceChangeListener, Observer<BlockchainServiceController> {
    private WalletActivity activity;
    private Configuration config;
    private BlockchainServiceController blockchainServiceController;

    private BlockchainStateUpdate blockchainStateUpdate = new BlockchainStateUpdate();
    private UpdateViewTask updateViewTask = new UpdateViewTask();

    @CheckForNull
    private BlockchainState blockchainState = null;

    private TextView messageView;

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (WalletActivity) activity;
        final WalletApplication application = (WalletApplication) activity.getApplication();
        this.config = application.getWalletClient().getConfiguration();
        this.blockchainServiceController = this.activity.getWalletClient().getBlockchainServiceController();
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        messageView = (TextView) inflater.inflate(R.layout.wallet_disclaimer_fragment, container);

        messageView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                final boolean showBackup = config.remindBackup();
                if (showBackup) {
                    activity.handleBackupWallet();
                } else {
                    HelpDialogFragment.page(getFragmentManager(), R.string.help_safety);
                }
            }
        });

        return messageView;
    }

    @Override
    public void onResume() {
        super.onResume();

        config.registerOnSharedPreferenceChangeListener(this);
        blockchainServiceController.addObserver(this);

        updateData();
        updateView();
    }

    @Override
    public void onPause() {
        blockchainServiceController.removeObserver(this);
        config.unregisterOnSharedPreferenceChangeListener(this);

        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (Configuration.PREFS_KEY_DISCLAIMER.equals(key) || Configuration.PREFS_KEY_REMIND_BACKUP.equals(key)) {
            updateView();
        }
    }

    private void updateView() {
        if (!isResumed()) {
            return;
        }

        final boolean showBackup = config.remindBackup();
        final boolean showDisclaimer = config.getDisclaimerEnabled();

        int progressResId = 0;
        if (blockchainState != null) {
            final Set<Impediment> impediments = blockchainState.impediments;
            if (impediments.contains(Impediment.STORAGE))
                progressResId = R.string.blockchain_state_progress_problem_storage;
            else if (impediments.contains(Impediment.NETWORK))
                progressResId = R.string.blockchain_state_progress_problem_network;
        }

        final SpannableStringBuilder text = new SpannableStringBuilder();
        if (progressResId != 0) {
            text.append(Html.fromHtml("<b>" + getString(progressResId) + "</b>"));
        }
        if (progressResId != 0 && (showBackup || showDisclaimer)) {
            text.append('\n');
        }
        if (showBackup) {
            text.append(Html.fromHtml(getString(R.string.wallet_disclaimer_fragment_remind_backup)));
        }
        if (showBackup && showDisclaimer) {
            text.append('\n');
        }
        if (showDisclaimer) {
            text.append(Html.fromHtml(getString(R.string.wallet_disclaimer_fragment_remind_safety)));
        }
        messageView.setText(text);

        final View view = getView();
        final ViewParent parent = view.getParent();
        final View fragment = parent instanceof FrameLayout ? (FrameLayout) parent : view;
        fragment.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void updateData() {
        Futures.addCallback(blockchainServiceController.loadBlockchainState(), blockchainStateUpdate);
    }

    @Override
    public void handleUpdate(BlockchainServiceController updatedObject) {
        updateData();
    }

    private class BlockchainStateUpdate implements FutureCallback<BlockchainState> {
        @Override
        public void onSuccess(BlockchainState result) {
            blockchainState = result;

            activity.getWalletClient().getGuiThreadExecutor().execute(updateViewTask);
        }

        @Override
        public void onFailure(Throwable t) {
             log.error("Failed to get blockchain state update ", t);
        }
    }

    private class UpdateViewTask implements Runnable {
        @Override
        public void run() {
            updateView();
        }
    }
}
