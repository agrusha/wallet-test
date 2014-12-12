package com.gowiper.wallet.send;


import android.content.Context;
import android.net.Uri;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.wallet.Constants;
import com.gowiper.wallet.data.AddressAndLabel;
import com.gowiper.wallet.data.BitcoinPayment;
import com.gowiper.wallet.parser.BinaryInputParser;
import com.gowiper.wallet.parser.StringInputParser;
import com.gowiper.wallet.util.AddressBookProvider;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Validate;
import org.bitcoinj.core.*;

import javax.annotation.Nonnull;

@Slf4j
public class PaymentProvider {
    private Context context;
    private Wallet wallet;
    private BitcoinPayment bitcoinPayment;
    private AddressAndLabel validatedAddress;
    @Getter @Setter private boolean highPriority;

    private PaymentProvider(Context context, Wallet wallet, BitcoinPayment bitcoinPayment){
        this.context = context;
        this.wallet = wallet;
        this.bitcoinPayment = bitcoinPayment;
    }

    public static PaymentProvider createFromBitcoinPayment(Context context, Wallet wallet, BitcoinPayment bitcoinPayment) {
        return new PaymentProvider(context, wallet, bitcoinPayment);
    }

    public static ListenableFuture<PaymentProvider> createFromBitcoinURI(final Context context, final Wallet wallet, final Uri bitcoinUri) {
        String input = bitcoinUri.toString();
        BitcoinUriParser bitcoinUriParser = new BitcoinUriParser(input);
        return Futures.transform(bitcoinUriParser.parseForPayment(), new BitcoinPaymentReceiver(context, wallet));
    }

    public static ListenableFuture<PaymentProvider> createFromPaymentRequest(Context context, Wallet wallet, String mimeType, byte[] request) {

        BinaryInputParser paymentRequestParser = new BinaryInputParser(mimeType, request);
        return Futures.transform(paymentRequestParser.parseForPayment(), new BitcoinPaymentReceiver(context, wallet));
    }

    private void updateStateFrom(BitcoinPayment bitcoinPayment) {
        this.bitcoinPayment = bitcoinPayment;
        validatedAddress = null;
    }

    private boolean validateReceivingAddress(String addressStr) {
        String address = Validate.notNull(addressStr);
        if(address.isEmpty()) {
            return false;
        } else {
            try {
                NetworkParameters networkParameters = Address.getParametersFromAddress(address);
                if (Constants.NETWORK_PARAMETERS.equals(networkParameters)) {
                    String label = AddressBookProvider.resolveLabel(context, address);
                    validatedAddress = new AddressAndLabel(Constants.NETWORK_PARAMETERS, address, label);
                } else {
                    log.warn("wrong network detected ");
                    return false;
                }
            } catch (WrongNetworkException ex) {
               log.error("wrong network detected ", ex);
                return false;
            } catch (AddressFormatException ex) {
               log.error("not valid address format ", ex);
                return false;
            }
        }
        return true;
    }

    private boolean validateAmount(Coin amount) {
        if(amount == null) {
            return false;
        } else {
            // NOTE: create fake transaction  and finalize it
            // just to make sure there would be no exceptions
            // concerned with coins amount (too much or too little)
            try{
                Address dummyAddress =  wallet.currentReceiveAddress();
                Wallet.SendRequest sendRequest = bitcoinPayment
                        .mergeWithEditedValues(amount, dummyAddress)
                        .toSendRequest();

                sendRequest.signInputs = false;
                sendRequest.emptyWallet = bitcoinPayment.mayEditAmount() &&
                        amount.equals(wallet.getBalance(Wallet.BalanceType.AVAILABLE));

                sendRequest.feePerKb = isHighPriority() ?
                        Wallet.SendRequest.DEFAULT_FEE_PER_KB.multiply(10) :
                        Wallet.SendRequest.DEFAULT_FEE_PER_KB;

                wallet.completeTx(sendRequest);
            } catch (Exception ex) {
                log.warn("Amount validation failed ", ex);
                return false;
            }
        }
        return true;
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
        private final Context context;
        private final Wallet wallet;
        @Override
        public PaymentProvider apply(BitcoinPayment payment) {
            return new PaymentProvider(context, wallet, payment);
        }
    }
}
