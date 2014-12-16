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
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.view.*;
import android.widget.ListView;
import com.gowiper.utils.observers.Observer;
import com.gowiper.wallet.Configuration;
import com.gowiper.wallet.Constants;
import com.gowiper.wallet.WalletApplication;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.controllers.WalletUpdateController.Direction;
import com.gowiper.wallet.controllers.WalletUpdateController;
import com.gowiper.wallet.util.AddressBookProvider;
import com.gowiper.wallet.util.GuiThreadExecutor;
import com.gowiper.wallet.util.Qr;
import com.gowiper.wallet.util.WalletUtils;
import de.schildbach.wallet.ui.util.BitmapFragment;
import de.schildbach.wallet_test.R;
import org.bitcoinj.core.*;
import org.bitcoinj.core.Transaction.Purpose;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * @author Andreas Schildbach
 */
public class TransactionsListFragment extends FancyListFragment implements Observer<WalletUpdateController> {
    private AbstractWalletActivity activity;
    private WalletClient walletClient;
    private Configuration config;
    private Wallet wallet;
    private WalletUpdateController walletUpdateController;
    private ContentResolver resolver;
    private TransactionsListAdapter adapter;
    private GuiThreadExecutor guiThreadExecutor;
    @CheckForNull
    private Direction direction;
    private final UpdateViewTask updateViewTask = new UpdateViewTask();
    private final Handler handler = new Handler();

    private static final String KEY_DIRECTION = "direction";
    private static final Uri KEY_ROTATION_URI = Uri.parse("http://bitcoin.org/en/alert/2013-08-11-android");

    public static TransactionsListFragment instance(@Nullable final Direction direction) {
        final TransactionsListFragment fragment = new TransactionsListFragment();

        final Bundle args = new Bundle();
        args.putSerializable(KEY_DIRECTION, direction);
        fragment.setArguments(args);

        return fragment;
    }

    private final ContentObserver addressBookObserver = new ContentObserver(handler) {
        @Override
        public void onChange(final boolean selfChange) {
            adapter.clearLabelCache();
        }
    };

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractWalletActivity) activity;
        this.walletClient = ((WalletApplication) activity.getApplication()).getWalletClient();
        this.config = walletClient.getConfiguration();
        this.wallet = walletClient.getWallet();
        this.walletUpdateController = walletClient.getWalletUpdateController();
        this.resolver = activity.getContentResolver();
        this.guiThreadExecutor = walletClient.getGuiThreadExecutor();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(false);

        this.direction = (Direction) getArguments().getSerializable(KEY_DIRECTION);

        final boolean showBackupWarning = direction == null || direction == WalletUpdateController.Direction.RECEIVED;

        adapter = new TransactionsListAdapter(activity, wallet, walletClient.maxConnectedPeers(), showBackupWarning);
        setListAdapter(adapter);
    }

    @Override
    public void onResume() {
        super.onResume();

        resolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true, addressBookObserver);

        walletUpdateController.addObserver(this);
        loadTransactions();

        updateView();
    }

    @Override
    public void onPause() {

        walletUpdateController.removeObserver(this);

        resolver.unregisterContentObserver(addressBookObserver);

        super.onPause();
    }

    @Override
    public void onListItemClick(final ListView l, final View v, final int position, final long id) {
        final Transaction tx = (Transaction) adapter.getItem(position);

        if (tx == null) {
            handleBackupWarningClick();
        } else if (tx.getPurpose() == Purpose.KEY_ROTATION) {
            handleKeyRotationClick();
        } else {
            handleTransactionClick(tx);
        }
    }

    @Override
    public void handleUpdate(WalletUpdateController updatedObject) {
        guiThreadExecutor.execute(updateViewTask);
    }

    private void loadTransactions() {
        updateTransactions(walletUpdateController.getTransactions(direction));
    }

    private void handleTransactionClick(@Nonnull final Transaction tx) {
        activity.startActionMode(new ActionMode.Callback() {
            private Address address;
            private byte[] serializedTx;

            private static final int SHOW_QR_THRESHOLD_BYTES = 2500;

            @Override
            public boolean onCreateActionMode(final ActionMode mode, final Menu menu) {
                final MenuInflater inflater = mode.getMenuInflater();
                inflater.inflate(R.menu.wallet_transactions_context, menu);

                return true;
            }

            @Override
            public boolean onPrepareActionMode(final ActionMode mode, final Menu menu) {
                try {
                    final Date time = tx.getUpdateTime();
                    final DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(activity);
                    final DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(activity);

                    mode.setTitle(time != null ? (DateUtils.isToday(time.getTime()) ? getString(R.string.time_today) : dateFormat.format(time))
                            + ", " + timeFormat.format(time) : null);

                    final Coin value = tx.getValue(wallet);
                    final boolean sent = value.signum() < 0;

                    address = sent ? WalletUtils.getWalletAddressOfReceived(tx, wallet) : WalletUtils.getFirstFromAddress(tx);

                    final String label;
                    if (tx.isCoinBase())
                        label = getString(R.string.wallet_transactions_fragment_coinbase);
                    else if (address != null)
                        label = AddressBookProvider.resolveLabel(activity, address.toString());
                    else
                        label = "?";

                    final String prefix = getString(sent ? R.string.symbol_to : R.string.symbol_from) + " ";

                    if (tx.getPurpose() != Purpose.KEY_ROTATION)
                        mode.setSubtitle(label != null ? prefix + label : WalletUtils.formatAddress(prefix, address,
                                Constants.ADDRESS_FORMAT_GROUP_SIZE, Constants.ADDRESS_FORMAT_LINE_SIZE));
                    else
                        mode.setSubtitle(null);

                    menu.findItem(R.id.wallet_transactions_context_edit_address).setVisible(address != null);

                    serializedTx = tx.unsafeBitcoinSerialize();

                    menu.findItem(R.id.wallet_transactions_context_show_qr).setVisible(serializedTx.length < SHOW_QR_THRESHOLD_BYTES);

                    return true;
                } catch (final ScriptException x) {
                    return false;
                }
            }

            @Override
            public boolean onActionItemClicked(final ActionMode mode, final MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.wallet_transactions_context_edit_address:
                        handleEditAddress(tx);

                        mode.finish();
                        return true;

                    case R.id.wallet_transactions_context_show_qr:
                        handleShowQr();

                        mode.finish();
                        return true;

                    case R.id.wallet_transactions_context_browse:
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Constants.EXPLORE_BASE_URL + "tx/" + tx.getHashAsString())));

                        mode.finish();
                        return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(final ActionMode mode) {
            }

            private void handleEditAddress(@Nonnull final Transaction tx) {
                EditAddressBookEntryFragment.edit(getFragmentManager(), address.toString());
            }

            private void handleShowQr() {
                final int size = getResources().getDimensionPixelSize(R.dimen.bitmap_dialog_qr_size);
                final Bitmap qrCodeBitmap = Qr.bitmap(Qr.encodeCompressBinary(serializedTx), size);
                BitmapFragment.show(getFragmentManager(), qrCodeBitmap);
            }
        });
    }

    private void handleKeyRotationClick() {
        startActivity(new Intent(Intent.ACTION_VIEW, KEY_ROTATION_URI));
    }

    private void handleBackupWarningClick() {
        ((WalletActivity) activity).handleBackupWallet();
    }

    private void updateView() {
        adapter.setFormat(config.getFormat());
        adapter.clearLabelCache();
    }

    private void updateTransactions(List<Transaction> newTransactionsList) {
        adapter.replace(newTransactionsList);

        final SpannableStringBuilder emptyText = new SpannableStringBuilder(
                getString(direction == WalletUpdateController.Direction.SENT ? R.string.wallet_transactions_fragment_empty_text_sent
                        : R.string.wallet_transactions_fragment_empty_text_received));
        emptyText.setSpan(new StyleSpan(Typeface.BOLD), 0, emptyText.length(), SpannableStringBuilder.SPAN_POINT_MARK);
        if (direction != WalletUpdateController.Direction.SENT) {
            emptyText.append("\n\n").append(getString(R.string.wallet_transactions_fragment_empty_text_howto));
        }

        setEmptyText(emptyText);
    }

    private class UpdateViewTask implements Runnable{
        @Override
        public void run() {
            updateView();
            loadTransactions();
        }
    }
}
