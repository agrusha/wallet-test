package com.gowiper.wallet.parser;

import com.gowiper.wallet.Constants;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocolException;

import javax.annotation.Nonnull;

@Slf4j
public class BinaryInputParser extends InputParser {
    private final String inputType;
    private final byte[] input;

    public BinaryInputParser(@Nonnull final String inputType, @Nonnull final byte[] input) {
        this.inputType = inputType;
        this.input = input;
    }

    @Override
    protected void parse() {
        if (Constants.MIMETYPE_TRANSACTION.equals(inputType)) {
            try {
                final Transaction tx = new Transaction(Constants.NETWORK_PARAMETERS, input);

                handleDirectTransaction(tx);
            } catch (final VerificationException x) {
                error(new Throwable("got invalid transaction", x));
            }
        } else if (PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(inputType)) {
            try {
                parseAndHandlePaymentRequest(input);
            } catch (final PaymentProtocolException.PkiVerificationException x) {
                error(new Throwable("got unverifyable payment request", x));
            } catch (final PaymentProtocolException x) {
                error(new Throwable("got invalid payment request", x));
            }
        } else {
            cannotClassify(inputType);
        }
    }

    @Override
    protected final void handlePrivateKey(@Nonnull final DumpedPrivateKey key) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected final void handleDirectTransaction(@Nonnull final Transaction transaction) throws VerificationException {
        throw new UnsupportedOperationException();
    }
}
