package de.schildbach.wallet.wallet;

import android.content.Context;
import android.text.format.DateUtils;
import com.google.common.base.Charsets;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.ExchangeRate;
import de.schildbach.wallet.util.Io;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.utils.Fiat;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.zip.GZIPInputStream;

@Slf4j
public class ExchangeRatesLoader extends AbstractLoader<Map<String,ExchangeRate>> {
    private static final URL BITCOINAVERAGE_URL;
    private static final String[] BITCOINAVERAGE_FIELDS = new String[]{"24h_avg", "last"};
    private static final String BITCOINAVERAGE_SOURCE = "BitcoinAverage.com";
    private static final URL BLOCKCHAININFO_URL;
    private static final String[] BLOCKCHAININFO_FIELDS = new String[]{"15m"};
    private static final String BLOCKCHAININFO_SOURCE = "blockchain.info";
    private static final long UPDATE_FREQ_MS = 10 * DateUtils.MINUTE_IN_MILLIS;

    static {
        try {
            BITCOINAVERAGE_URL = new URL("https://api.bitcoinaverage.com/custom/abw");
            BLOCKCHAININFO_URL = new URL("https://blockchain.info/ticker");
        } catch (final MalformedURLException x) {
            throw new RuntimeException(x); // cannot happen
        }
    }

    private final Configuration config;
    private final String userAgent;

    private Map<String, ExchangeRate> exchangeRates = null;
    private long lastUpdated = 0;

    public ExchangeRatesLoader(WalletClient client) {
        super(client.getBackgroundExecutor());
        Context context = client.getApplicationContext();
        this.config = client.getConfiguration();
        this.userAgent = WalletClient.httpUserAgent(WalletClient.packageInfoFromContext(context).versionName);

        final ExchangeRate cachedExchangeRate = config.getCachedExchangeRate();
        if (cachedExchangeRate != null) {
            exchangeRates = new TreeMap<String, ExchangeRate>();
            exchangeRates.put(cachedExchangeRate.getCurrencyCode(), cachedExchangeRate);
        }
    }


    @Override
    protected Map<String, ExchangeRate> getData() {
        final long now = System.currentTimeMillis();
        Map<String, ExchangeRate> newExchangeRates;
        newExchangeRates = requestExchangeRates(BITCOINAVERAGE_URL, userAgent, BITCOINAVERAGE_SOURCE, BITCOINAVERAGE_FIELDS);

        if (newExchangeRates == null) {
            newExchangeRates = requestExchangeRates(BLOCKCHAININFO_URL, userAgent, BLOCKCHAININFO_SOURCE, BLOCKCHAININFO_FIELDS);
        }

        if (newExchangeRates != null) {
            exchangeRates = newExchangeRates;
            lastUpdated = now;

            final ExchangeRate exchangeRateToCache = bestExchangeRate(config.getExchangeCurrencyCode());
            if (exchangeRateToCache != null) {
                config.setCachedExchangeRate(exchangeRateToCache);
            }
        }

        return exchangeRates;
    }

    private ExchangeRate bestExchangeRate(final String currencyCode) {
        ExchangeRate rate = currencyCode != null ? exchangeRates.get(currencyCode) : null;
        if (rate != null) {
            return rate;
        }

        final String defaultCode = defaultCurrencyCode();
        rate = defaultCode != null ? exchangeRates.get(defaultCode) : null;

        if (rate != null) {
            return rate;
        }

        return exchangeRates.get(Constants.DEFAULT_EXCHANGE_CURRENCY);
    }

    private String defaultCurrencyCode() {
        try {
            return Currency.getInstance(Locale.getDefault()).getCurrencyCode();
        } catch (final IllegalArgumentException x) {
            return null;
        }
    }

    private static Map<String, ExchangeRate> requestExchangeRates(final URL url, final String userAgent, final String source, final String... fields) {
        final long start = System.currentTimeMillis();

        HttpURLConnection connection = null;
        Reader reader = null;

        try {
            connection = (HttpURLConnection) url.openConnection();

            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.addRequestProperty("User-Agent", userAgent);
            connection.addRequestProperty("Accept-Encoding", "gzip");
            connection.connect();

            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                final String contentEncoding = connection.getContentEncoding();

                InputStream is = new BufferedInputStream(connection.getInputStream(), 1024);
                if ("gzip".equalsIgnoreCase(contentEncoding))
                    is = new GZIPInputStream(is);

                reader = new InputStreamReader(is, Charsets.UTF_8);
                final StringBuilder content = new StringBuilder();
                final long length = Io.copy(reader, content);

                final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();

                final JSONObject head = new JSONObject(content.toString());
                for (final Iterator<String> i = head.keys(); i.hasNext(); ) {
                    final String currencyCode = i.next();
                    if (!"timestamp".equals(currencyCode)) {
                        final JSONObject o = head.getJSONObject(currencyCode);

                        for (final String field : fields) {
                            final String rateStr = o.optString(field, null);

                            if (rateStr != null) {
                                try {
                                    final Fiat rate = Fiat.parseFiat(currencyCode, rateStr);

                                    if (rate.signum() > 0) {
                                        rates.put(currencyCode, new ExchangeRate(new org.bitcoinj.utils.ExchangeRate(rate), source));
                                        break;
                                    }
                                } catch (final NumberFormatException x) {
                                    log.warn("problem fetching {} exchange rate from {} ({}): {}", currencyCode, url, contentEncoding, x.getMessage());
                                }
                            }
                        }
                    }
                }

                log.info("fetched exchange rates from {} ({}), {} chars, took {} ms", url, contentEncoding, length, System.currentTimeMillis()
                        - start);

                return rates;
            } else {
                log.warn("http status {} when fetching exchange rates from {}", responseCode, url);
            }
        } catch (final Exception x) {
            log.warn("problem fetching exchange rates from " + url, x);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException x) {
                    // swallow
                }
            }

            if (connection != null) {
                connection.disconnect();
            }
        }

        return null;
    }
}
