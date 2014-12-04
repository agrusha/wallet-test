package de.schildbach.wallet;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(suppressConstructorProperties = true)
public class ExchangeRate {

    public final org.bitcoinj.utils.ExchangeRate rate;
    public final String source;

    public String getCurrencyCode() {
        return rate.fiat.currencyCode;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '[' + rate.fiat + ']';
    }
}
