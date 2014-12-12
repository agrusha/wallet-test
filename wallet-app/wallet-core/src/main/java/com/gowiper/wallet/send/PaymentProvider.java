package com.gowiper.wallet.send;


import android.content.Context;
import android.net.Uri;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.*;
import com.gowiper.wallet.Constants;
import com.gowiper.wallet.WalletClient;
import com.gowiper.wallet.data.AddressAndLabel;
import com.gowiper.wallet.data.BitcoinPayment;
import com.gowiper.wallet.parser.BinaryInputParser;
import com.gowiper.wallet.parser.StreamInputParser;
import com.gowiper.wallet.parser.StringInputParser;
import com.gowiper.wallet.util.AddressBookProvider;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.bitcoinj.core.*;
import org.spongycastle.crypto.params.KeyParameter;

import javax.annotation.Nonnull;
import java.io.InputStream;
import java.util.concurrent.Callable;

@Slf4j
public class PaymentProvider {
    private final Context context;
    private final Wallet wallet;
    private final ListeningScheduledExecutorService backgroundExecutor;
    private final SendCoinsBackgroundTask sendCoinsBackgroundTask;
    @Getter private BitcoinPayment bitcoinPayment;
    @Getter @Setter private boolean highPriority;

    private PaymentProvider(WalletClient walletClient,
                            BitcoinPayment bitcoinPayment){
        this.context = walletClient.getApplicationContext();
        this.wallet = walletClient.getWallet();
        this.backgroundExecutor = walletClient.getBackgroundExecutor();
        this.sendCoinsBackgroundTask = new SendCoinsBackgroundTask(wallet, backgroundExecutor);
        this.bitcoinPayment = bitcoinPayment;
    }

    public static PaymentProvider createFromBitcoinPayment(WalletClient walletClient,
                                                           BitcoinPayment bitcoinPayment) {
        return new PaymentProvider(walletClient, bitcoinPayment);
    }

    public static ListenableFuture<PaymentProvider> createFromBitcoinURI(WalletClient walletClient,
                                                                         final Uri bitcoinUri) {
        String input = bitcoinUri.toString();
        return createFromURIString(walletClient, input);
    }

    public static ListenableFuture<PaymentProvider> createFromURIString(WalletClient walletClient,
                                                                        final String paymentData) {
        BitcoinUriParser bitcoinUriParser = new BitcoinUriParser(paymentData);
        return Futures.transform(bitcoinUriParser.parseForPayment(), new BitcoinPaymentReceiver(walletClient));
    }

    public static ListenableFuture<PaymentProvider> createFromPaymentRequest(WalletClient walletClient,
                                                                             String mimeType, byte[] request) {

        BinaryInputParser paymentRequestParser = new BinaryInputParser(mimeType, request);
        return Futures.transform(paymentRequestParser.parseForPayment(), new BitcoinPaymentReceiver(walletClient));
    }

    public static ListenableFuture<PaymentProvider> createFromStream(WalletClient walletClient,
                                                                     String mimeType, InputStream inputStream) {
        StreamInputParser streamInputParser = new StreamInputParser(mimeType, inputStream);
        return Futures.transform(streamInputParser.parseForPayment(), new BitcoinPaymentReceiver(walletClient));
    }

    public Optional<AddressAndLabel> validateReceivingAddress(String addressStr) {
        String address = Validate.notNull(addressStr);
        if(address.isEmpty()) {
            return Optional.absent();
        } else {
            try {
                NetworkParameters networkParameters = Address.getParametersFromAddress(address);
                if (Constants.NETWORK_PARAMETERS.equals(networkParameters)) {
                    String label = AddressBookProvider.resolveLabel(context, address);
                    AddressAndLabel validatedAddress = new AddressAndLabel(Constants.NETWORK_PARAMETERS, address, label);
                    return Optional.of(validatedAddress);
                } else {
                    log.warn("wrong network detected ");
                    return Optional.absent();
                }
            } catch (WrongNetworkException ex) {
               log.error("wrong network detected ", ex);
                return Optional.absent();
            } catch (AddressFormatException ex) {
               log.error("not valid address format ", ex);
                return Optional.absent();
            }
        }
    }

    public ListenableFuture<Boolean> validateAmount(Coin amount) {
        return backgroundExecutor.submit(new AmountValidationTask(amount));
    }

    private boolean checkAmount(Coin amount) throws InsufficientMoneyException {
        if(amount == null) {
            return false;
        } else {
            // NOTE: create fake transaction  and finalize it
            // just to make sure there would be no exceptions
            // concerned with coins amount (too much or too little)
            Address dummyAddress =  wallet.currentReceiveAddress();
            Wallet.SendRequest sendRequest = bitcoinPayment
                    .mergeWithEditedValues(amount, dummyAddress)
                    .toSendRequest();

            sendRequest.signInputs = false;
            sendRequest.emptyWallet = bitcoinPayment.mayEditAmount() &&
                    amount.equals(wallet.getBalance(Wallet.BalanceType.AVAILABLE));

            sendRequest.feePerKb = getFeePerKb();

            wallet.completeTx(sendRequest);
        }
        return true;
    }

    private Coin getFeePerKb() {
        return isHighPriority() ? Wallet.SendRequest.DEFAULT_FEE_PER_KB.multiply(10) :
                Wallet.SendRequest.DEFAULT_FEE_PER_KB;
    }

    public ListenableFuture<BitcoinPayment> updatePaymentDetails(final Coin amount, String address) {
        final Optional<AddressAndLabel> validatedAddress = validateReceivingAddress(address);
        if (validatedAddress.isPresent()) {
            return Futures.transform(validateAmount(amount), new Function<Boolean, BitcoinPayment>() {
                @Override
                public BitcoinPayment apply(Boolean input) {
                    bitcoinPayment = bitcoinPayment.mergeWithEditedValues(amount, validatedAddress.get().address);
                    return bitcoinPayment;
                }
            });
        } else {
            return Futures.immediateFailedFuture(new Throwable("address is invalid"));
        }
    }

    public ListenableFuture<Transaction> updateSignAndSend(Coin amount, String address, final KeyParameter key) {
        final SettableFuture<Transaction> futureTransaction = SettableFuture.create();
        Futures.addCallback(updatePaymentDetails(amount, address), new FutureCallback<BitcoinPayment>() {
            @Override
            public void onSuccess(BitcoinPayment result) {
                Futures.addCallback(signAndSendPayment(key), new FutureCallback<Transaction>() {
                    @Override
                    public void onSuccess(Transaction result) {
                        futureTransaction.set(result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        futureTransaction.setException(t);
                    }
                });
            }

            @Override
            public void onFailure(Throwable t) {
                futureTransaction.setException(t);
            }
        });
        return futureTransaction;
    }

    public ListenableFuture<Transaction> signAndSendPayment(KeyParameter encryptionKey) {
        Wallet.SendRequest sendRequest = bitcoinPayment.toSendRequest();
        sendRequest.emptyWallet = bitcoinPayment.getAmount().equals(wallet.getBalance(Wallet.BalanceType.AVAILABLE));
        sendRequest.feePerKb = getFeePerKb();
        sendRequest.memo = bitcoinPayment.memo;
        sendRequest.aesKey = encryptionKey;

        return sendCoinsBackgroundTask.sendCoinsAsync(sendRequest);
    }

    private static class BitcoinUriParser extends StringInputParser {
        public BitcoinUriParser(@Nonnull String input) {
            super(input);
        }

        @Override
        protected void handlePrivateKey(@Nonnull final DumpedPrivateKey key) {
            log.warn("Private Keys are not supposed to be here");
            throw new UnsupportedOperationException();
        }

        @Override
        protected void handleDirectTransaction(@Nonnull Transaction transaction) throws VerificationException {
            throw new UnsupportedOperationException();
        }
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private static class BitcoinPaymentReceiver implements  Function<BitcoinPayment, PaymentProvider> {
        private final WalletClient walletClient;
        @Override
        public PaymentProvider apply(BitcoinPayment payment) {
            return new PaymentProvider(walletClient, payment);
        }
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private class AmountValidationTask implements Callable<Boolean> {
        private final Coin amount;
        @Override
        public Boolean call() throws Exception {
            return checkAmount(amount);
        }
    }
}
