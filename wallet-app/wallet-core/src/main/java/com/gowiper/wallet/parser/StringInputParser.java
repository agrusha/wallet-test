package com.gowiper.wallet.parser;

import com.gowiper.wallet.Constants;
import com.gowiper.wallet.data.BitcoinPayment;
import com.gowiper.wallet.util.Qr;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.*;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.uri.BitcoinURI;
import org.bitcoinj.uri.BitcoinURIParseException;

import javax.annotation.Nonnull;
import java.io.IOException;

@Slf4j
public class StringInputParser extends InputParser {
    private final String input;


    public StringInputParser(@Nonnull final String input) {
        this.input = input;
    }

    @Override
    public void parse() {
        if (input.startsWith("BITCOIN:-")) {
            try {
                final byte[] serializedPaymentRequest = Qr.decodeBinary(input.substring(9));

                parseAndHandlePaymentRequest(serializedPaymentRequest);
            } catch (final IOException x) {
                error(new Throwable("i/o error while fetching payment request", x));
            } catch (final PaymentProtocolException.PkiVerificationException x) {
                error(new Throwable("got unverifyable payment request", x));
            } catch (final PaymentProtocolException x) {
                error(new Throwable("got invalid payment request", x));
            }
        } else if (input.startsWith("bitcoin:")) {
            try {
                final BitcoinURI bitcoinUri = new BitcoinURI(null, input);
                final Address address = bitcoinUri.getAddress();
                if (address != null && !Constants.NETWORK_PARAMETERS.equals(address.getParameters())) {
                    throw new BitcoinURIParseException("mismatched network");
                }

                handleBitcoinPayment(BitcoinPayment.fromBitcoinUri(bitcoinUri));
            } catch (final BitcoinURIParseException x) {
                error(new Throwable("got invalid bitcoin uri: '" + input + "'", x));
            }
        } else if (PATTERN_BITCOIN_ADDRESS.matcher(input).matches()) {
            try {
                final Address address = new Address(Constants.NETWORK_PARAMETERS, input);

                handleBitcoinPayment(BitcoinPayment.fromAddress(address, null));
            } catch (final AddressFormatException x) {
                error(new Throwable("got invalid address", x));
            }
        } else if (PATTERN_PRIVATE_KEY_UNCOMPRESSED.matcher(input).matches() || PATTERN_PRIVATE_KEY_COMPRESSED.matcher(input).matches()) {
            try {
                final DumpedPrivateKey key = new DumpedPrivateKey(Constants.NETWORK_PARAMETERS, input);

                handlePrivateKey(key);
            } catch (final AddressFormatException x) {
                error(new Throwable("got invalid address", x));
            }
        } else if (PATTERN_TRANSACTION.matcher(input).matches()) {
            try {
                final Transaction tx = new Transaction(Constants.NETWORK_PARAMETERS, Qr.decodeDecompressBinary(input));

                handleDirectTransaction(tx);
            } catch (final IOException x) {
                error(new Throwable("i/o error while fetching transaction", x));
            } catch (final ProtocolException x) {
                error(new Throwable("got invalid transaction", x));
            }
        } else {
            cannotClassify(input);
        }
    }
}
