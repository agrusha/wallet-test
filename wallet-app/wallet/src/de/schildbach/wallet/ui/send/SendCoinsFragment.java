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

package de.schildbach.wallet.ui.send;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.widget.*;
import android.widget.CompoundButton.OnCheckedChangeListener;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.gowiper.wallet.*;
import com.gowiper.wallet.data.AddressAndLabel;
import com.gowiper.wallet.data.BitcoinPayment;
import com.gowiper.wallet.data.ExchangeRate;
import com.gowiper.wallet.offline.DirectPaymentTask;
import com.gowiper.wallet.send.PaymentProvider;
import com.gowiper.wallet.util.AddressBookProvider;
import com.gowiper.wallet.util.Bluetooth;
import com.gowiper.wallet.util.Nfc;
import com.gowiper.wallet.util.WalletUtils;
import de.schildbach.wallet.integration.android.BitcoinIntegration;
import de.schildbach.wallet.ui.*;
import de.schildbach.wallet.ui.util.CurrencyCalculatorLink;
import de.schildbach.wallet_test.R;
import org.bitcoin.protocols.payments.Protos.Payment;
import org.bitcoinj.core.*;
import org.bitcoinj.core.TransactionConfidence.ConfidenceType;
import org.bitcoinj.core.Wallet.BalanceType;
import org.bitcoinj.core.Wallet.CouldNotAdjustDownwards;
import org.bitcoinj.core.Wallet.DustySendRequested;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.KeyChain.KeyPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * @author Andreas Schildbach
 */
public final class SendCoinsFragment extends Fragment {
    private AbstractBindServiceActivity activity;
    private WalletClient walletClient;
    private Configuration config;
    private Wallet wallet;
    private ContentResolver contentResolver;
    private LoaderManager loaderManager;
    private FragmentManager fragmentManager;
    @CheckForNull
    private BluetoothAdapter bluetoothAdapter;

    private final Handler handler = new Handler();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private TextView payeeNameView;
    private TextView payeeVerifiedByView;
    private AutoCompleteTextView receivingAddressView;
    private ReceivingAddressViewAdapter receivingAddressViewAdapter;
    private View receivingStaticView;
    private TextView receivingStaticAddressView;
    private TextView receivingStaticLabelView;
    private CurrencyCalculatorLink amountCalculatorLink;
    private CheckBox directPaymentEnableView;

    private TextView hintView;
    private TextView directPaymentMessageView;
    private ListView sentTransactionView;
    private TransactionsListAdapter sentTransactionListAdapter;
    private View privateKeyPasswordViewGroup;
    private EditText privateKeyPasswordView;
    private View privateKeyBadPasswordView;
    private Button viewGo;
    private Button viewCancel;

    private State state = State.INPUT;

    private PaymentProvider paymentProvider;
    private boolean validatedAmount;
    private BitcoinPayment bitcoinPayment = null;
    private boolean priority = false;
    private AddressAndLabel validatedAddress = null;

    private Transaction sentTransaction = null;
    private Boolean directPaymentAck = null;

//    private Transaction dryrunTransaction;
    private Exception dryrunException;

    private static final int ID_RATE_LOADER = 0;
    private static final int ID_RECEIVING_ADDRESS_LOADER = 1;

    private static final int REQUEST_CODE_SCAN = 0;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST = 1;
    private static final int REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT = 2;

    private static final Logger log = LoggerFactory.getLogger(SendCoinsFragment.class);

    private enum State {
        INPUT, DECRYPTING, SIGNING, SENDING, SENT, FAILED
    }

    private final class ReceivingAddressListener implements OnFocusChangeListener, TextWatcher {
        @Override
        public void onFocusChange(final View v, final boolean hasFocus) {
            if (!hasFocus) {
                validateReceivingAddress();
                updateView();
            }
        }

        @Override
        public void afterTextChanged(final Editable s) {
            if (s.length() > 0)
                validateReceivingAddress();
            else
                updateView();
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
        }
    }

    private final ReceivingAddressListener receivingAddressListener = new ReceivingAddressListener();

    private final class ReceivingAddressActionMode implements ActionMode.Callback {
        private final Address address;

        public ReceivingAddressActionMode(final Address address) {
            this.address = address;
        }

        @Override
        public boolean onCreateActionMode(final ActionMode mode, final Menu menu) {
            final MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.send_coins_address_context, menu);

            return true;
        }

        @Override
        public boolean onPrepareActionMode(final ActionMode mode, final Menu menu) {
            menu.findItem(R.id.send_coins_address_context_clear).setVisible(bitcoinPayment.mayEditAddress());

            return true;
        }

        @Override
        public boolean onActionItemClicked(final ActionMode mode, final MenuItem item) {
            switch (item.getItemId()) {
                case R.id.send_coins_address_context_edit_address:
                    handleEditAddress();

                    mode.finish();
                    return true;

                case R.id.send_coins_address_context_clear:
                    handleClear();

                    mode.finish();
                    return true;
            }

            return false;
        }

        @Override
        public void onDestroyActionMode(final ActionMode mode) {
            if (receivingStaticView.hasFocus())
                requestFocusFirst();
        }

        private void handleEditAddress() {
            EditAddressBookEntryFragment.edit(fragmentManager, address.toString());
        }

        private void handleClear() {
            // switch from static to input
            validatedAddress = null;
            receivingAddressView.setText(null);
            receivingStaticAddressView.setText(null);

            updateView();

            requestFocusFirst();
        }
    }

    private final CurrencyAmountView.Listener amountsListener = new CurrencyAmountView.Listener() {
        @Override
        public void changed() {
            updateView();
            validateEnteredAmount();
        }

        @Override
        public void focusChanged(final boolean hasFocus) {
        }
    };

    private final TextWatcher privateKeyPasswordListener = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            privateKeyBadPasswordView.setVisibility(View.INVISIBLE);
            updateView();
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    private final ContentObserver contentObserver = new ContentObserver(handler) {
        @Override
        public void onChange(final boolean selfChange) {
            updateView();
        }
    };

    private final TransactionConfidence.Listener sentTransactionConfidenceListener = new TransactionConfidence.Listener() {
        @Override
        public void onConfidenceChanged(final Transaction tx, final TransactionConfidence.Listener.ChangeReason reason) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!isResumed())
                        return;

                    sentTransactionListAdapter.notifyDataSetChanged();

                    final TransactionConfidence confidence = sentTransaction.getConfidence();
                    final ConfidenceType confidenceType = confidence.getConfidenceType();
                    final int numBroadcastPeers = confidence.numBroadcastPeers();

                    if (state == State.SENDING) {
                        if (confidenceType == ConfidenceType.DEAD)
                            setState(State.FAILED);
                        else if (numBroadcastPeers > 1 || confidenceType == ConfidenceType.BUILDING)
                            setState(State.SENT);
                    }

                    if (reason == ChangeReason.SEEN_PEERS && confidenceType == ConfidenceType.PENDING) {
                        // play sound effect
                        final int soundResId = getResources().getIdentifier("send_coins_broadcast_" + numBroadcastPeers, "raw",
                                activity.getPackageName());
                        if (soundResId > 0)
                            RingtoneManager.getRingtone(activity, Uri.parse("android.resource://" + activity.getPackageName() + "/" + soundResId))
                                    .play();
                    }
                }
            });
        }
    };

    private final LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ExchangeRateLoader(activity, config);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);

                if (state == State.INPUT) {
                    amountCalculatorLink.setExchangeRate(exchangeRate.rate);
                }
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    private final LoaderCallbacks<Cursor> receivingAddressLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            final String constraint = args != null ? args.getString("constraint") : null;
            return new CursorLoader(activity, AddressBookProvider.contentUri(activity.getPackageName()), null, AddressBookProvider.SELECTION_QUERY,
                    new String[]{constraint != null ? constraint : ""}, null);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> cursor, final Cursor data) {
            receivingAddressViewAdapter.swapCursor(data);
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> cursor) {
            receivingAddressViewAdapter.swapCursor(null);
        }
    };

    private final class ReceivingAddressViewAdapter extends CursorAdapter implements FilterQueryProvider {
        public ReceivingAddressViewAdapter(final Context context) {
            super(context, null, false);
            setFilterQueryProvider(this);
        }

        @Override
        public View newView(final Context context, final Cursor cursor, final ViewGroup parent) {
            final LayoutInflater inflater = LayoutInflater.from(context);
            return inflater.inflate(R.layout.address_book_row, parent, false);
        }

        @Override
        public void bindView(final View view, final Context context, final Cursor cursor) {
            final String label = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_LABEL));
            final String address = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));

            final ViewGroup viewGroup = (ViewGroup) view;
            final TextView labelView = (TextView) viewGroup.findViewById(R.id.address_book_row_label);
            labelView.setText(label);
            final TextView addressView = (TextView) viewGroup.findViewById(R.id.address_book_row_address);
            addressView.setText(WalletUtils.formatHash(address, Constants.ADDRESS_FORMAT_GROUP_SIZE, Constants.ADDRESS_FORMAT_LINE_SIZE));
        }

        @Override
        public CharSequence convertToString(final Cursor cursor) {
            return cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));
        }

        @Override
        public Cursor runQuery(final CharSequence constraint) {
            final Bundle args = new Bundle();
            if (constraint != null)
                args.putString("constraint", constraint.toString());
            loaderManager.restartLoader(ID_RECEIVING_ADDRESS_LOADER, args, receivingAddressLoaderCallbacks);
            return getCursor();
        }
    }

    private final DialogInterface.OnClickListener activityDismissListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(final DialogInterface dialog, final int which) {
            activity.finish();
        }
    };

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        this.activity = (AbstractBindServiceActivity) activity;
        this.walletClient = ((WalletApplication) activity.getApplication()).getWalletClient();
        this.config = walletClient.getConfiguration();
        this.wallet = walletClient.getWallet();
        this.contentResolver = activity.getContentResolver();
        this.loaderManager = getLoaderManager();
        this.fragmentManager = getFragmentManager();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRetainInstance(true);
        setHasOptionsMenu(true);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        } else {
            final Intent intent = activity.getIntent();
            final String action = intent.getAction();
            final Uri intentUri = intent.getData();
            final String scheme = intentUri != null ? intentUri.getScheme() : null;
            final String mimeType = intent.getType();

            if ((Intent.ACTION_VIEW.equals(action) || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) && intentUri != null
                    && "bitcoin".equals(scheme)) {
                initStateFromBitcoinUri(intentUri);
            } else if ((NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {
                final NdefMessage ndefMessage = (NdefMessage) intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)[0];
                final byte[] ndefMessagePayload = Nfc.extractMimePayload(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, ndefMessage);
                initStateFromPaymentRequest(mimeType, ndefMessagePayload);
            } else if ((Intent.ACTION_VIEW.equals(action)) && PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(mimeType)) {
                final byte[] paymentRequest = BitcoinIntegration.paymentRequestFromIntent(intent);

                if (intentUri != null)
                    initStateFromIntentUri(mimeType, intentUri);
                else if (paymentRequest != null)
                    initStateFromPaymentRequest(mimeType, paymentRequest);
                else
                    throw new IllegalArgumentException();
            } else if (intent.hasExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT)) {
                initStateFromIntentExtras(intent.getExtras());
            } else {
                paymentProvider = PaymentProvider.createFromBitcoinPayment(walletClient, BitcoinPayment.blank());
                updateStateFrom(BitcoinPayment.blank());
            }
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.send_coins_fragment, container);

        payeeNameView = (TextView) view.findViewById(R.id.send_coins_payee_name);
        payeeVerifiedByView = (TextView) view.findViewById(R.id.send_coins_payee_verified_by);

        receivingAddressView = (AutoCompleteTextView) view.findViewById(R.id.send_coins_receiving_address);
        receivingAddressViewAdapter = new ReceivingAddressViewAdapter(activity);
        receivingAddressView.setAdapter(receivingAddressViewAdapter);
        receivingAddressView.setOnFocusChangeListener(receivingAddressListener);
        receivingAddressView.addTextChangedListener(receivingAddressListener);

        receivingStaticView = view.findViewById(R.id.send_coins_receiving_static);
        receivingStaticAddressView = (TextView) view.findViewById(R.id.send_coins_receiving_static_address);
        receivingStaticLabelView = (TextView) view.findViewById(R.id.send_coins_receiving_static_label);

        receivingStaticView.setOnFocusChangeListener(new OnFocusChangeListener() {
            private ActionMode actionMode;

            @Override
            public void onFocusChange(final View v, final boolean hasFocus) {
                if (hasFocus) {
                    final Address address = bitcoinPayment.hasAddress() ? bitcoinPayment.getAddress()
                            : (validatedAddress != null ? validatedAddress.address : null);
                    if (address != null)
                        actionMode = activity.startActionMode(new ReceivingAddressActionMode(address));
                } else {
                    actionMode.finish();
                }
            }
        });

        final CurrencyAmountView btcAmountView = (CurrencyAmountView) view.findViewById(R.id.send_coins_amount_btc);
        btcAmountView.setCurrencySymbol(config.getFormat().code());
        btcAmountView.setInputFormat(config.getMaxPrecisionFormat());
        btcAmountView.setHintFormat(config.getFormat());

        final CurrencyAmountView localAmountView = (CurrencyAmountView) view.findViewById(R.id.send_coins_amount_local);
        localAmountView.setInputFormat(Constants.LOCAL_FORMAT);
        localAmountView.setHintFormat(Constants.LOCAL_FORMAT);
        amountCalculatorLink = new CurrencyCalculatorLink(btcAmountView, localAmountView);
        amountCalculatorLink.setExchangeDirection(config.getLastExchangeDirection());

        directPaymentEnableView = (CheckBox) view.findViewById(R.id.send_coins_direct_payment_enable);
        directPaymentEnableView.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                if (bitcoinPayment.isBluetoothPaymentUrl() && isChecked && !bluetoothAdapter.isEnabled()) {
                    // ask for permission to enable bluetooth
                    startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT);
                }
            }
        });

        hintView = (TextView) view.findViewById(R.id.send_coins_hint);

        directPaymentMessageView = (TextView) view.findViewById(R.id.send_coins_direct_payment_message);

        sentTransactionView = (ListView) view.findViewById(R.id.send_coins_sent_transaction);
        sentTransactionListAdapter = new TransactionsListAdapter(activity, wallet, walletClient.maxConnectedPeers(), false);
        sentTransactionView.setAdapter(sentTransactionListAdapter);

        privateKeyPasswordViewGroup = view.findViewById(R.id.send_coins_private_key_password_group);
        privateKeyPasswordView = (EditText) view.findViewById(R.id.send_coins_private_key_password);
        privateKeyBadPasswordView = view.findViewById(R.id.send_coins_private_key_bad_password);

        viewGo = (Button) view.findViewById(R.id.send_coins_go);
        viewGo.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                validateReceivingAddress();

                if (everythingValid())
                    handleGo();
                else
                    requestFocusFirst();

                updateView();
            }
        });

        viewCancel = (Button) view.findViewById(R.id.send_coins_cancel);
        viewCancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View v) {
                handleCancel();
            }
        });

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        config.setLastExchangeDirection(amountCalculatorLink.getExchangeDirection());
    }

    @Override
    public void onResume() {
        super.onResume();

        contentResolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true, contentObserver);

        amountCalculatorLink.setListener(amountsListener);
        privateKeyPasswordView.addTextChangedListener(privateKeyPasswordListener);

        loaderManager.initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
        loaderManager.initLoader(ID_RECEIVING_ADDRESS_LOADER, null, receivingAddressLoaderCallbacks);

        updateView();
        validateEnteredAmount();
    }

    @Override
    public void onPause() {
        loaderManager.destroyLoader(ID_RECEIVING_ADDRESS_LOADER);
        loaderManager.destroyLoader(ID_RATE_LOADER);

        privateKeyPasswordView.removeTextChangedListener(privateKeyPasswordListener);
        amountCalculatorLink.setListener(null);

        contentResolver.unregisterContentObserver(contentObserver);

        super.onPause();
    }

    @Override
    public void onDetach() {
        handler.removeCallbacksAndMessages(null);

        super.onDetach();
    }

    @Override
    public void onDestroy() {
        backgroundThread.getLooper().quit();

        if (sentTransaction != null)
            sentTransaction.getConfidence().removeEventListener(sentTransactionConfidenceListener);

        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);

        saveInstanceState(outState);
    }

    private void saveInstanceState(final Bundle outState) {
        outState.putSerializable("state", state);

        outState.putParcelable("payment_intent", bitcoinPayment);
        outState.putBoolean("priority", priority);
        if (validatedAddress != null)
            outState.putParcelable("validated_address", validatedAddress);

        if (sentTransaction != null)
            outState.putSerializable("sent_transaction_hash", sentTransaction.getHash());
        if (directPaymentAck != null)
            outState.putBoolean("direct_payment_ack", directPaymentAck);
    }

    private void restoreInstanceState(final Bundle savedInstanceState) {
        state = (State) savedInstanceState.getSerializable("state");
        bitcoinPayment = (BitcoinPayment) savedInstanceState.getParcelable("payment_intent");
        paymentProvider = PaymentProvider.createFromBitcoinPayment(walletClient, bitcoinPayment);
        priority = savedInstanceState.getBoolean("priority");
        paymentProvider.setHighPriority(priority);
        validatedAddress = savedInstanceState.getParcelable("validated_address");

        if (savedInstanceState.containsKey("sent_transaction_hash")) {
            sentTransaction = wallet.getTransaction((Sha256Hash) savedInstanceState.getSerializable("sent_transaction_hash"));
            sentTransaction.getConfidence().addEventListener(sentTransactionConfidenceListener);
        }
        if (savedInstanceState.containsKey("direct_payment_ack")) {
            directPaymentAck = savedInstanceState.getBoolean("direct_payment_ack");
        }
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                onActivityResultResumed(requestCode, resultCode, intent);
            }
        });
    }

    private void onActivityResultResumed(final int requestCode, final int resultCode, final Intent intent) {
        if (requestCode == REQUEST_CODE_SCAN) {
            if (resultCode == Activity.RESULT_OK) {
                final String input = intent.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT);

                Futures.addCallback(PaymentProvider.createFromURIString(walletClient, input), new FutureCallback<PaymentProvider>() {
                    @Override
                    public void onSuccess(PaymentProvider result) {
                        paymentProvider = result;
                        updateStateFrom(paymentProvider.getBitcoinPayment());
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        log.error("failed to get payment data ", t);
                    }
                }, walletClient.getGuiThreadExecutor());
            }
        } else if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST) {
            if (bitcoinPayment.isBluetoothPaymentRequestUrl())
                requestPaymentRequest();
        } else if (requestCode == REQUEST_CODE_ENABLE_BLUETOOTH_FOR_DIRECT_PAYMENT) {
            if (bitcoinPayment.isBluetoothPaymentUrl())
                directPaymentEnableView.setChecked(resultCode == Activity.RESULT_OK);
        }
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        inflater.inflate(R.menu.send_coins_fragment_options, menu);

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(final Menu menu) {
        final MenuItem scanAction = menu.findItem(R.id.send_coins_options_scan);
        final PackageManager pm = activity.getPackageManager();
        scanAction.setVisible(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));
        scanAction.setEnabled(state == State.INPUT);

        final MenuItem emptyAction = menu.findItem(R.id.send_coins_options_empty);
        emptyAction.setEnabled(state == State.INPUT);

        final MenuItem priorityAction = menu.findItem(R.id.send_coins_options_priority);
        priorityAction.setChecked(priority);
        priorityAction.setEnabled(state == State.INPUT);

        super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.send_coins_options_scan:
                handleScan();
                return true;

            case R.id.send_coins_options_priority:
                handlePriority();
                return true;

            case R.id.send_coins_options_empty:
                handleEmpty();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void validateReceivingAddress() {
        final String addressStr = receivingAddressView.getText().toString().trim();

        Optional<AddressAndLabel> optionalAddress = paymentProvider.validateReceivingAddress(addressStr);
        if (optionalAddress.isPresent()) {
            validatedAddress = optionalAddress.get();
            receivingAddressView.setText(null);
        }
    }

    private void handleCancel() {
        if (state == State.INPUT)
            activity.setResult(Activity.RESULT_CANCELED);

        activity.finish();
    }

    private boolean isOutputsValid() {
        return bitcoinPayment.hasOutputs() || validatedAddress != null;
    }

    private boolean isAmountValid() {
        return validatedAmount;
    }

    private boolean isPasswordValid() {
        return !wallet.isEncrypted() || !privateKeyPasswordView.getText().toString().trim().isEmpty();

    }

    private boolean everythingValid() {
        return state == State.INPUT && isOutputsValid() && isAmountValid() && isPasswordValid();
    }

    private void requestFocusFirst() {
        if (!isOutputsValid()) {
            receivingAddressView.requestFocus();
        } else if (!isAmountValid()) {
            amountCalculatorLink.requestFocus();
        } else if (everythingValid()) {
            viewGo.requestFocus();
        } else {
            log.warn("unclear focus");
        }
    }

    private void handleGo() {
        privateKeyBadPasswordView.setVisibility(View.INVISIBLE);
        final String addressStr = validatedAddress.address.toString();
        final Coin finalAmount = amountCalculatorLink.getAmount();

        if (wallet.isEncrypted()) {
            new DeriveKeyTask(backgroundHandler) {
                @Override
                protected void onSuccess(@Nonnull KeyParameter encryptionKey) {
                    Futures.addCallback(paymentProvider.updateSignAndSend(finalAmount, addressStr, encryptionKey), new FutureCallback<Transaction>() {
                        @Override
                        public void onSuccess(Transaction result) {
                            onTransactionSuccess(result);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            onTransactionFailure(t);
                        }
                    }, walletClient.getGuiThreadExecutor());
                }
            }.deriveKey(wallet.getKeyCrypter(), privateKeyPasswordView.getText().toString().trim());

            setState(State.DECRYPTING);
        } else {
            Futures.addCallback(paymentProvider.updateSignAndSend(finalAmount, addressStr, null), new FutureCallback<Transaction>() {
                @Override
                public void onSuccess(Transaction result) {
                    onTransactionSuccess(result);
                }

                @Override
                public void onFailure(Throwable t) {
                    onTransactionFailure(t);
                }
            }, walletClient.getGuiThreadExecutor());
        }
    }

    private void onTransactionSuccess(Transaction transaction) {
        sentTransaction = transaction;
        Coin finalAmount = paymentProvider.getBitcoinPayment().getAmount();
        setState(State.SENDING);

        sentTransaction.getConfidence().addEventListener(sentTransactionConfidenceListener);

        final Address refundAddress = bitcoinPayment.standard == BitcoinPayment.Standard.BIP70 ? wallet.freshAddress(KeyPurpose.REFUND) : null;
        final Payment payment = PaymentProtocol.createPaymentMessage(Arrays.asList(sentTransaction), finalAmount,
                refundAddress, null, bitcoinPayment.payeeData);

        if (directPaymentEnableView.isChecked()) {
            directPay(payment);
        }

        walletClient.broadcastTransaction(sentTransaction);

        final ComponentName callingActivity = activity.getCallingActivity();
        if (callingActivity != null) {
            log.info("returning result to calling activity: {}", callingActivity.flattenToString());

            final Intent result = new Intent();
            BitcoinIntegration.transactionHashToResult(result, sentTransaction.getHashAsString());
            if (bitcoinPayment.standard == BitcoinPayment.Standard.BIP70) {
                BitcoinIntegration.paymentToResult(result, payment.toByteArray());
            }
            activity.setResult(Activity.RESULT_OK, result);
        }
    }

    private void onTransactionFailure(Throwable throwable) {
        if(throwable instanceof InsufficientMoneyException) {
            InsufficientMoneyException exception =
                    (InsufficientMoneyException) throwable;
            onInsufficientMoney(exception.missing);
        } else if (throwable instanceof KeyCrypterException) {
            onInvalidKey();
        } else if (throwable instanceof CouldNotAdjustDownwards) {
            onEmptyWalletFailed();
        } else if (throwable instanceof Wallet.CompletionException) {
            Wallet.CompletionException exception =
                    (Wallet.CompletionException) throwable;
            onFailure(exception);
        }
    }


    private void directPay(final Payment payment) {
        final DirectPaymentTask.ResultCallback callback = new DirectPaymentTask.ResultCallback() {
            @Override
            public void onResult(final boolean ack) {
                directPaymentAck = ack;

                if (state == State.SENDING)
                    setState(State.SENT);

                updateView();
            }

            @Override
            public void onFail(final Object... messageArgs) {
                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_direct_payment_failed_title);
                dialog.setMessage(bitcoinPayment.paymentUrl + "\n" + getString(R.string.error_http, messageArgs) + "\n\n"
                        + getString(R.string.send_coins_fragment_direct_payment_failed_msg));
                dialog.setPositiveButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        directPay(payment);
                    }
                });
                dialog.setNegativeButton(R.string.button_dismiss, null);
                dialog.show();
            }
        };

        if (bitcoinPayment.isHttpPaymentUrl()) {
            new DirectPaymentTask.HttpPaymentTask(backgroundHandler, callback, bitcoinPayment.paymentUrl, walletClient.httpUserAgent())
                    .send(payment);
        } else if (bitcoinPayment.isBluetoothPaymentUrl() && bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            new DirectPaymentTask.BluetoothPaymentTask(backgroundHandler, callback, bluetoothAdapter,
                    Bluetooth.getBluetoothMac(bitcoinPayment.paymentUrl)).send(payment);
        }
    }

    protected void onInsufficientMoney(@Nonnull final Coin missing) {
        setState(State.INPUT);

        final Coin estimated = wallet.getBalance(BalanceType.ESTIMATED);
        final Coin available = wallet.getBalance(BalanceType.AVAILABLE);
        final Coin pending = estimated.subtract(available);

        final MonetaryFormat btcFormat = config.getFormat();

        final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_insufficient_money_title);
        final StringBuilder msg = new StringBuilder();
        msg.append(getString(R.string.send_coins_fragment_insufficient_money_msg1, btcFormat.format(missing)));
        msg.append("\n\n");
        if (pending.signum() > 0)
            msg.append(getString(R.string.send_coins_fragment_pending, btcFormat.format(pending))).append("\n\n");
        msg.append(getString(R.string.send_coins_fragment_insufficient_money_msg2));
        dialog.setMessage(msg);
        dialog.setPositiveButton(R.string.send_coins_options_empty, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(final DialogInterface dialog, final int which) {
                handleEmpty();
            }
        });
        dialog.setNegativeButton(R.string.button_cancel, null);
        dialog.show();
    }

    protected void onInvalidKey() {
        setState(State.INPUT);

        privateKeyBadPasswordView.setVisibility(View.VISIBLE);
        privateKeyPasswordView.requestFocus();
    }

    protected void onEmptyWalletFailed() {
        setState(State.INPUT);

        final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_empty_wallet_failed_title);
        dialog.setMessage(R.string.send_coins_fragment_hint_empty_wallet_failed);
        dialog.setNeutralButton(R.string.button_dismiss, null);
        dialog.show();
    }

    protected void onFailure(@Nonnull Exception exception) {
        setState(State.FAILED);

        final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_error_msg);
        dialog.setMessage(exception.toString());
        dialog.setNeutralButton(R.string.button_dismiss, null);
        dialog.show();
    }


    private void handleScan() {
        startActivityForResult(new Intent(activity, ScanActivity.class), REQUEST_CODE_SCAN);
    }

    private void handlePriority() {
        priority = !priority;
        paymentProvider.setHighPriority(priority);
        updateView();
        validateEnteredAmount();
    }

    private void handleEmpty() {
        final Coin available = wallet.getBalance(BalanceType.AVAILABLE);
        amountCalculatorLink.setBtcAmount(available);

        updateView();
        validateEnteredAmount();
    }

    private void validateEnteredAmount() {
        final Coin amount = amountCalculatorLink.getAmount();
        Futures.addCallback(paymentProvider.validateAmount(amount), new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                validatedAmount = result.booleanValue();
                updateView();
            }

            @Override
            public void onFailure(Throwable t) {
                validatedAmount = false;
                dryrunException = (Exception) t;
                updateView();
            }
        }, walletClient.getGuiThreadExecutor());
    }

    private void setState(final State state) {
        this.state = state;

        activity.invalidateOptionsMenu();
        updateView();
    }

    private void updateView() {
        if (bitcoinPayment != null) {
            final MonetaryFormat btcFormat = config.getFormat();

            getView().setVisibility(View.VISIBLE);

            if (bitcoinPayment.hasPayee()) {
                payeeNameView.setVisibility(View.VISIBLE);
                payeeNameView.setText(bitcoinPayment.payeeName);

                payeeVerifiedByView.setVisibility(View.VISIBLE);
                final String verifiedBy = bitcoinPayment.payeeVerifiedBy != null ? bitcoinPayment.payeeVerifiedBy
                        : getString(R.string.send_coins_fragment_payee_verified_by_unknown);
                payeeVerifiedByView.setText(Constants.CHAR_CHECKMARK
                        + String.format(getString(R.string.send_coins_fragment_payee_verified_by), verifiedBy));
            } else {
                payeeNameView.setVisibility(View.GONE);
                payeeVerifiedByView.setVisibility(View.GONE);
            }

            if (bitcoinPayment.hasOutputs()) {
                receivingAddressView.setVisibility(View.GONE);
                receivingStaticView.setVisibility(View.VISIBLE);

                receivingStaticLabelView.setText(bitcoinPayment.memo);

                if (bitcoinPayment.hasAddress())
                    receivingStaticAddressView.setText(WalletUtils.formatAddress(bitcoinPayment.getAddress(), Constants.ADDRESS_FORMAT_GROUP_SIZE,
                            Constants.ADDRESS_FORMAT_LINE_SIZE));
                else
                    receivingStaticAddressView.setText(R.string.send_coins_fragment_receiving_address_complex);
            } else if (validatedAddress != null) {
                receivingAddressView.setVisibility(View.GONE);

                receivingStaticView.setVisibility(View.VISIBLE);
                receivingStaticAddressView.setText(WalletUtils.formatAddress(validatedAddress.address, Constants.ADDRESS_FORMAT_GROUP_SIZE,
                        Constants.ADDRESS_FORMAT_LINE_SIZE));
                final String addressBookLabel = AddressBookProvider.resolveLabel(activity, validatedAddress.address.toString());
                final String staticLabel;
                if (addressBookLabel != null)
                    staticLabel = addressBookLabel;
                else if (validatedAddress.label != null)
                    staticLabel = validatedAddress.label;
                else
                    staticLabel = getString(R.string.address_unlabeled);
                receivingStaticLabelView.setText(staticLabel);
                receivingStaticLabelView.setTextColor(getResources().getColor(
                        validatedAddress.label != null ? R.color.fg_significant : R.color.fg_insignificant));
            } else {
                receivingStaticView.setVisibility(View.GONE);

                receivingAddressView.setVisibility(View.VISIBLE);
            }

            receivingAddressView.setEnabled(state == State.INPUT);

            receivingStaticView.setEnabled(state == State.INPUT);

            amountCalculatorLink.setEnabled(state == State.INPUT && bitcoinPayment.mayEditAmount());

            final boolean directPaymentVisible;
            if (bitcoinPayment.hasPaymentUrl()) {
                if (bitcoinPayment.isBluetoothPaymentUrl())
                    directPaymentVisible = bluetoothAdapter != null;
                else
                    directPaymentVisible = !Constants.BUG_OPENSSL_HEARTBLEED;
            } else {
                directPaymentVisible = false;
            }
            directPaymentEnableView.setVisibility(directPaymentVisible ? View.VISIBLE : View.GONE);
            directPaymentEnableView.setEnabled(state == State.INPUT);

            hintView.setVisibility(View.GONE);
            if (state == State.INPUT) {
                if (bitcoinPayment.mayEditAddress() && validatedAddress == null && !receivingAddressView.getText().toString().trim().isEmpty()) {
                    hintView.setTextColor(getResources().getColor(R.color.fg_error));
                    hintView.setVisibility(View.VISIBLE);
                    hintView.setText(R.string.send_coins_fragment_receiving_address_error);
                } else if (dryrunException != null) {
                    hintView.setTextColor(getResources().getColor(R.color.fg_error));
                    hintView.setVisibility(View.VISIBLE);
                    if (dryrunException instanceof DustySendRequested) {
                        hintView.setText(getString(R.string.send_coins_fragment_hint_dusty_send));
                    } else if (dryrunException instanceof InsufficientMoneyException) {
                        hintView.setText(getString(R.string.send_coins_fragment_hint_insufficient_money,
                                btcFormat.format(((InsufficientMoneyException) dryrunException).missing)));
                    } else if (dryrunException instanceof CouldNotAdjustDownwards) {
                        hintView.setText(getString(R.string.send_coins_fragment_hint_empty_wallet_failed));
                    } else {
                        hintView.setText(dryrunException.toString());
                    }
                }
            }

            if (sentTransaction != null) {
                sentTransactionView.setVisibility(View.VISIBLE);
                sentTransactionListAdapter.setFormat(btcFormat);
                sentTransactionListAdapter.replace(sentTransaction);
            } else {
                sentTransactionView.setVisibility(View.GONE);
                sentTransactionListAdapter.clear();
            }

            if (directPaymentAck != null) {
                directPaymentMessageView.setVisibility(View.VISIBLE);
                directPaymentMessageView.setText(directPaymentAck ? R.string.send_coins_fragment_direct_payment_ack
                        : R.string.send_coins_fragment_direct_payment_nack);
            } else {
                directPaymentMessageView.setVisibility(View.GONE);
            }

            viewCancel.setEnabled(state != State.DECRYPTING && state != State.SIGNING);
            viewGo.setEnabled(everythingValid());

            if (state == State.INPUT) {
                viewCancel.setText(R.string.button_cancel);
                viewGo.setText(R.string.send_coins_fragment_button_send);
            } else if (state == State.DECRYPTING) {
                viewCancel.setText(R.string.button_cancel);
                viewGo.setText(R.string.send_coins_fragment_state_decrypting);
            } else if (state == State.SIGNING) {
                viewCancel.setText(R.string.button_cancel);
                viewGo.setText(R.string.send_coins_preparation_msg);
            } else if (state == State.SENDING) {
                viewCancel.setText(R.string.send_coins_fragment_button_back);
                viewGo.setText(R.string.send_coins_sending_msg);
            } else if (state == State.SENT) {
                viewCancel.setText(R.string.send_coins_fragment_button_back);
                viewGo.setText(R.string.send_coins_sent_msg);
            } else if (state == State.FAILED) {
                viewCancel.setText(R.string.send_coins_fragment_button_back);
                viewGo.setText(R.string.send_coins_failed_msg);
            }

            final boolean privateKeyPasswordViewVisible = (state == State.INPUT || state == State.DECRYPTING) && wallet.isEncrypted();
            privateKeyPasswordViewGroup.setVisibility(privateKeyPasswordViewVisible ? View.VISIBLE : View.GONE);
            privateKeyPasswordView.setEnabled(state == State.INPUT);

            // focus linking
            final int activeAmountViewId = amountCalculatorLink.activeTextView().getId();
            receivingAddressView.setNextFocusDownId(activeAmountViewId);
            receivingAddressView.setNextFocusForwardId(activeAmountViewId);
            receivingStaticView.setNextFocusDownId(activeAmountViewId);
            amountCalculatorLink.setNextFocusId(privateKeyPasswordViewVisible ? R.id.send_coins_private_key_password : R.id.send_coins_go);
            privateKeyPasswordView.setNextFocusUpId(activeAmountViewId);
            privateKeyPasswordView.setNextFocusDownId(R.id.send_coins_go);
            viewGo.setNextFocusUpId(privateKeyPasswordViewVisible ? R.id.send_coins_private_key_password : activeAmountViewId);
        } else {
            getView().setVisibility(View.GONE);
        }
    }

    private void initStateFromIntentExtras(@Nonnull final Bundle extras) {
        final BitcoinPayment bitcoinPayment = extras.getParcelable(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT);

        paymentProvider = PaymentProvider.createFromBitcoinPayment(walletClient, bitcoinPayment);
        updateStateFrom(bitcoinPayment);
    }

    private void initStateFromBitcoinUri(@Nonnull final Uri bitcoinUri) {
        Futures.addCallback(PaymentProvider.createFromBitcoinURI(walletClient, bitcoinUri), new FutureCallback<PaymentProvider>() {
            @Override
            public void onSuccess(PaymentProvider result) {
                paymentProvider = result;
                updateStateFrom(paymentProvider.getBitcoinPayment());
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to get payment data ", t);
                updateStateFrom(BitcoinPayment.blank());
            }
        }, walletClient.getGuiThreadExecutor());
    }

    private void initStateFromPaymentRequest(@Nonnull final String mimeType, @Nonnull final byte[] input) {

        Futures.addCallback(PaymentProvider.createFromPaymentRequest(walletClient, mimeType, input), new FutureCallback<PaymentProvider>() {
            @Override
            public void onSuccess(PaymentProvider result) {
                paymentProvider = result;
                updateStateFrom(paymentProvider.getBitcoinPayment());
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Failed to get payment data ", t);
                updateStateFrom(BitcoinPayment.blank());
            }
        }, walletClient.getGuiThreadExecutor());
    }

    private void initStateFromIntentUri(@Nonnull final String mimeType, @Nonnull final Uri bitcoinUri) {
        try {
            final InputStream is = contentResolver.openInputStream(bitcoinUri);

            Futures.addCallback(PaymentProvider.createFromStream(walletClient, mimeType, is), new FutureCallback<PaymentProvider>() {
                @Override
                public void onSuccess(PaymentProvider result) {
                    paymentProvider = result;
                    updateStateFrom(paymentProvider.getBitcoinPayment());
                }

                @Override
                public void onFailure(Throwable t) {
                    log.error("Failed to get payment data ", t);
                    updateStateFrom(BitcoinPayment.blank());
                }
            }, walletClient.getGuiThreadExecutor());
        } catch (final FileNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    private void updateStateFrom(final @Nonnull BitcoinPayment bitcoinPayment) {
        log.info("got {}", bitcoinPayment);

        this.bitcoinPayment = bitcoinPayment;

        validatedAddress = null;
        directPaymentAck = null;

        // delay these actions until fragment is resumed
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (state == State.INPUT) {
                    receivingAddressView.setText(null);
                    amountCalculatorLink.setBtcAmount(bitcoinPayment.getAmount());

                    if (bitcoinPayment.isBluetoothPaymentUrl()) {
                        directPaymentEnableView.setChecked(bluetoothAdapter != null && bluetoothAdapter.isEnabled());
                    } else if (bitcoinPayment.isHttpPaymentUrl()) {
                        directPaymentEnableView.setChecked(!Constants.BUG_OPENSSL_HEARTBLEED);
                    }

                    requestFocusFirst();
                    updateView();
                    validateEnteredAmount();
                }

                if (bitcoinPayment.hasPaymentRequestUrl()) {
                    if (bitcoinPayment.isBluetoothPaymentRequestUrl() && !Constants.BUG_OPENSSL_HEARTBLEED) {
                        if (bluetoothAdapter.isEnabled()) {
                            requestPaymentRequest();
                        } else {
                            // ask for permission to enable bluetooth
                            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                                    REQUEST_CODE_ENABLE_BLUETOOTH_FOR_PAYMENT_REQUEST);
                        }
                    } else if (bitcoinPayment.isHttpPaymentRequestUrl()) {
                        requestPaymentRequest();
                    }
                }
            }
        });
    }

    private void requestPaymentRequest() {
        final String host;
        if (!Bluetooth.isBluetoothUrl(bitcoinPayment.paymentRequestUrl))
            host = Uri.parse(bitcoinPayment.paymentRequestUrl).getHost();
        else
            host = Bluetooth.decompressMac(Bluetooth.getBluetoothMac(bitcoinPayment.paymentRequestUrl));

        ProgressDialogFragment.showProgress(fragmentManager, getString(R.string.send_coins_fragment_request_payment_request_progress, host));

        final RequestPaymentResultCallback callback = new RequestPaymentResultCallback();

        if (!Bluetooth.isBluetoothUrl(bitcoinPayment.paymentRequestUrl)) {
            new RequestPaymentRequestTask.HttpRequestTask(backgroundHandler, callback, walletClient.httpUserAgent())
                    .requestPaymentRequest(bitcoinPayment.paymentRequestUrl);
        } else {
            new RequestPaymentRequestTask.BluetoothRequestTask(backgroundHandler, callback, bluetoothAdapter)
                    .requestPaymentRequest(bitcoinPayment.paymentRequestUrl);
        }
    }

    private class RequestPaymentResultCallback implements RequestPaymentRequestTask.ResultCallback {
        @Override
        public void onPayment (final BitcoinPayment bitcoinPayment) {
            ProgressDialogFragment.dismissProgress(fragmentManager);

            if (SendCoinsFragment.this.bitcoinPayment.isExtendedBy(bitcoinPayment)) {
                // success
                updateStateFrom(bitcoinPayment);
                updateView();
                validateEnteredAmount();
            } else {
                final StringBuilder reasons = new StringBuilder();
                if (!SendCoinsFragment.this.bitcoinPayment.equalsAddress(bitcoinPayment))
                    reasons.append("address");
                if (!SendCoinsFragment.this.bitcoinPayment.equalsAmount(bitcoinPayment))
                    reasons.append(reasons.length() == 0 ? "" : ", ").append("amount");
                if (reasons.length() == 0)
                    reasons.append("unknown");

                final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_request_payment_request_failed_title);
                dialog.setMessage(getString(R.string.send_coins_fragment_request_payment_request_wrong_signature) + "\n\n" + reasons);
                dialog.singleDismissButton(null);
                dialog.show();

                log.info("BIP72 trust check failed: {}", reasons);
            }
        }

        @Override
        public void onFail(final int messageResId, final Object... messageArgs) {
            ProgressDialogFragment.dismissProgress(fragmentManager);

            final DialogBuilder dialog = DialogBuilder.warn(activity, R.string.send_coins_fragment_request_payment_request_failed_title);
            dialog.setMessage(getString(messageResId, messageArgs));
            dialog.setPositiveButton(R.string.button_retry, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int which) {
                    requestPaymentRequest();
                }
            });
            dialog.setNegativeButton(R.string.button_dismiss, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int which) {
                    if (!bitcoinPayment.hasOutputs())
                        handleCancel();
                }
            });
            dialog.show();
        }
    }
}
