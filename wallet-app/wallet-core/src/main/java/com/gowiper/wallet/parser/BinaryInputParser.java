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
public abstract class BinaryInputParser extends InputParser {
    private final String inputType;
    private final byte[] input;

    public BinaryInputParser(@Nonnull final String inputType, @Nonnull final byte[] input) {
        this.inputType = inputType;
        this.input = input;
    }

    @Override
    public void parse() {
        if (Constants.MIMETYPE_TRANSACTION.equals(inputType)) {
            try {
                final Transaction tx = new Transaction(Constants.NETWORK_PARAMETERS, input);

                handleDirectTransaction(tx);
            } catch (final VerificationException x) {
                log.info("got invalid transaction", x);

//                    error(R.string.input_parser_invalid_transaction, x.getMessage());
            }
        } else if (PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(inputType)) {
            try {
                parseAndHandlePaymentRequest(input);
            } catch (final PaymentProtocolException.PkiVerificationException x) {
                log.info("got unverifyable payment request", x);

//                    error(R.string.input_parser_unverifyable_paymentrequest, x.getMessage());
            } catch (final PaymentProtocolException x) {
                log.info("got invalid payment request", x);

//                    error(R.string.input_parser_invalid_paymentrequest, x.getMessage());
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
