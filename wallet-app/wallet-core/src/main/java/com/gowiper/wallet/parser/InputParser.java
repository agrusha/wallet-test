package com.gowiper.wallet.parser;

import android.content.Context;
import android.content.DialogInterface.OnClickListener;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.UninitializedMessageException;
import com.gowiper.wallet.Constants;
import com.gowiper.wallet.data.BitcoinPayment;
import com.gowiper.wallet.data.PaymentOutput;
import lombok.extern.slf4j.Slf4j;
import org.bitcoin.protocols.payments.Protos;
import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TrustStoreLoader;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocol.PkiVerificationData;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.protocols.payments.PaymentSession;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Pattern;

@Slf4j
public abstract class InputParser {

    public static final Pattern PATTERN_BITCOIN_ADDRESS = Pattern.compile("[" + new String(Base58.ALPHABET) + "]{20,40}");
    public static final Pattern PATTERN_PRIVATE_KEY_UNCOMPRESSED = Pattern.compile((Constants.NETWORK_PARAMETERS.getId().equals(
            NetworkParameters.ID_MAINNET) ? "5" : "9")
            + "[" + new String(Base58.ALPHABET) + "]{50}");
    public static final Pattern PATTERN_PRIVATE_KEY_COMPRESSED = Pattern.compile((Constants.NETWORK_PARAMETERS.getId().equals(
            NetworkParameters.ID_MAINNET) ? "[KL]" : "c")
            + "[" + new String(Base58.ALPHABET) + "]{51}");
    public static final Pattern PATTERN_TRANSACTION = Pattern.compile("[0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ$\\*\\+\\-\\.\\/\\:]{100,}");

    protected SettableFuture<BitcoinPayment> futurePayment = SettableFuture.create();
    protected SettableFuture<DumpedPrivateKey> futurePrivateKey = SettableFuture.create();
    protected SettableFuture<Transaction> futureTransaction = SettableFuture.create();

    protected abstract void parse();

    protected void error(Throwable throwable) {
        futurePayment.setException(throwable);
        futurePrivateKey.setException(throwable);
        futureTransaction.setException(throwable);
    }

    public ListenableFuture<BitcoinPayment> parseForPayment() {
        parse();
        return futurePayment;
    }

    public ListenableFuture<DumpedPrivateKey> parseForPrivateKey() {
        parse();
        return futurePrivateKey;
    }

    public ListenableFuture<Transaction> parseForTransaction() {
        parse();
        return futureTransaction;
    }

    protected void handleBitcoinPayment(@Nonnull BitcoinPayment bitcoinPayment) {
        futurePayment.set(bitcoinPayment);
    }

    protected void handleDirectTransaction(@Nonnull Transaction transaction) throws VerificationException {
        futureTransaction.set(transaction);
    }

    protected final void parseAndHandlePaymentRequest(@Nonnull final byte[] serializedPaymentRequest) throws PaymentProtocolException {
        final BitcoinPayment bitcoinPayment = parsePaymentRequest(serializedPaymentRequest);

        handleBitcoinPayment(bitcoinPayment);
    }

    public static BitcoinPayment parsePaymentRequest(@Nonnull final byte[] serializedPaymentRequest) throws PaymentProtocolException {
        try {
            if (serializedPaymentRequest.length > 50000) {
                throw new PaymentProtocolException("payment request too big: " + serializedPaymentRequest.length);
            }

            final Protos.PaymentRequest paymentRequest = Protos.PaymentRequest.parseFrom(serializedPaymentRequest);

            final String pkiName;
            final String pkiCaName;
            if (!"none".equals(paymentRequest.getPkiType())) {
                final KeyStore keystore = new TrustStoreLoader.DefaultTrustStoreLoader().getKeyStore();
                final PkiVerificationData verificationData = PaymentProtocol.verifyPaymentRequestPki(paymentRequest, keystore);
                pkiName = verificationData.displayName;
                pkiCaName = verificationData.rootAuthorityName;
            } else {
                pkiName = null;
                pkiCaName = null;
            }

            final PaymentSession paymentSession = PaymentProtocol.parsePaymentRequest(paymentRequest);

            if (paymentSession.isExpired()) {
                throw new PaymentProtocolException.Expired("payment details expired: current time " + new Date() + " after expiry time "
                        + paymentSession.getExpires());
            }

            if (!paymentSession.getNetworkParameters().equals(Constants.NETWORK_PARAMETERS)) {
                throw new PaymentProtocolException.InvalidNetwork("cannot handle payment request network: " + paymentSession.getNetworkParameters());
            }

            final ArrayList<PaymentOutput> outputs = new ArrayList<PaymentOutput>(1);
            for (final PaymentProtocol.Output output : paymentSession.getOutputs()) {
                outputs.add(PaymentOutput.valueOf(output));
            }

            final String memo = paymentSession.getMemo();

            final String paymentUrl = paymentSession.getPaymentUrl();

            final byte[] merchantData = paymentSession.getMerchantData();

            final byte[] paymentRequestHash = Hashing.sha256().hashBytes(serializedPaymentRequest).asBytes();

            final BitcoinPayment bitcoinPayment = new BitcoinPayment(BitcoinPayment.Standard.BIP70, pkiName, pkiCaName,
                    outputs.toArray(new PaymentOutput[0]), memo, paymentUrl, merchantData, null, paymentRequestHash);

            if (bitcoinPayment.hasPaymentUrl() && !bitcoinPayment.isSupportedPaymentUrl()) {
                throw new PaymentProtocolException.InvalidPaymentURL("cannot handle payment url: " + bitcoinPayment.paymentUrl);
            }

            return bitcoinPayment;
        } catch (final InvalidProtocolBufferException x) {
            throw new PaymentProtocolException(x);
        } catch (final UninitializedMessageException x) {
            throw new PaymentProtocolException(x);
        } catch (final FileNotFoundException x) {
            throw new RuntimeException(x);
        } catch (final KeyStoreException x) {
            throw new RuntimeException(x);
        }
    }

    protected void handlePrivateKey(@Nonnull final DumpedPrivateKey key) {
        futurePrivateKey.set(key);

        final Address address = new Address(Constants.NETWORK_PARAMETERS, key.getKey().getPubKeyHash());
        handleBitcoinPayment(BitcoinPayment.fromAddress(address, null));
    }

    public void cannotClassify(@Nonnull final String input) {
        error(new Throwable("Can not classify input ["+ input +']'));
    }

    protected void dialog(final Context context, @Nullable final OnClickListener dismissListener, final int titleResId, final int messageResId,
                          final Object... messageArgs) {
        // TODO move it out here ASAP
//        final DialogBuilder dialog = new DialogBuilder(context);
//        if (titleResId != 0)
//            dialog.setTitle(titleResId);
//        dialog.setMessage(context.getString(messageResId, messageArgs));
//        dialog.singleDismissButton(dismissListener);
//        dialog.show();
    }
}
