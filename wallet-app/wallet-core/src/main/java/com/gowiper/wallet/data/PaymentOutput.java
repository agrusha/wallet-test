package com.gowiper.wallet.data;

import android.os.Parcel;
import android.os.Parcelable;
import com.gowiper.wallet.Constants;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.protocols.payments.PaymentProtocol;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import org.bitcoinj.script.Script;

import java.util.Arrays;

@RequiredArgsConstructor(suppressConstructorProperties = true)
public final class PaymentOutput implements Parcelable {
    @Getter private final Coin amount;
    @Getter private final Script script;

    public static PaymentOutput valueOf(final PaymentProtocol.Output output) throws PaymentProtocolException.InvalidOutputs {
        try {
            final Script script = new Script(output.scriptData);
            return new PaymentOutput(output.amount, script);
        } catch (final ScriptException x) {
            throw new PaymentProtocolException.InvalidOutputs("unparseable script in output: " + Arrays.toString(output.scriptData));
        }
    }

    public boolean hasAmount() {
        return amount != null && amount.signum() != 0;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder();

        builder.append(getClass().getSimpleName());
        builder.append('[');
        builder.append(hasAmount() ? amount.toPlainString() : "null");
        builder.append(',');
        if (script.isSentToAddress() || script.isPayToScriptHash()) {
            builder.append(script.getToAddress(Constants.NETWORK_PARAMETERS));
        } else if (script.isSentToRawPubKey()) {
            for (final byte b : script.getPubKey()) {
                builder.append(String.format("%02x", b));
            }
        } else if (script.isSentToMultiSig()) {
            builder.append("multisig");
        } else {
            builder.append("unknown");
        }
        builder.append(']');

        return builder.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(final Parcel dest, final int flags) {
        dest.writeSerializable(amount);

        final byte[] program = script.getProgram();
        dest.writeInt(program.length);
        dest.writeByteArray(program);
    }

    public static final Creator<PaymentOutput> CREATOR = new Creator<PaymentOutput>() {
        @Override
        public PaymentOutput createFromParcel(final Parcel in) {
            return new PaymentOutput(in);
        }

        @Override
        public PaymentOutput[] newArray(final int size) {
            return new PaymentOutput[size];
        }
    };

    private PaymentOutput(final Parcel in) {
        amount = (Coin) in.readSerializable();

        final int programLength = in.readInt();
        final byte[] program = new byte[programLength];
        in.readByteArray(program);
        script = new Script(program);
    }
}
