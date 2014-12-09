package com.gowiper.wallet.parser;

import com.gowiper.wallet.util.Io;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocolException;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
public abstract class StreamInputParser extends InputParser {
    private final String inputType;
    private final InputStream is;

    public StreamInputParser(@Nonnull final String inputType, @Nonnull final InputStream is) {
        this.inputType = inputType;
        this.is = is;
    }

    @Override
    public void parse() {
        if (PaymentProtocol.MIMETYPE_PAYMENTREQUEST.equals(inputType)) {
            ByteArrayOutputStream baos = null;

            try {
                baos = new ByteArrayOutputStream();
                Io.copy(is, baos);
                parseAndHandlePaymentRequest(baos.toByteArray());
            } catch (final IOException x) {
                log.info("i/o error while fetching payment request", x);

//                    error(R.string.input_parser_io_error, x.getMessage());
            } catch (final PaymentProtocolException.PkiVerificationException x) {
                log.info("got unverifyable payment request", x);

//                    error(R.string.input_parser_unverifyable_paymentrequest, x.getMessage());
            } catch (final PaymentProtocolException x) {
                log.info("got invalid payment request", x);

//                    error(R.string.input_parser_invalid_paymentrequest, x.getMessage());
            } finally {
                try {
                    if (baos != null)
                        baos.close();
                } catch (IOException x) {
                    x.printStackTrace();
                }

                try {
                    is.close();
                } catch (IOException x) {
                    x.printStackTrace();
                }
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
