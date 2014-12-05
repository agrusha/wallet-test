package com.gowiper.wallet.util;

import android.content.res.AssetManager;
import com.google.common.base.Charsets;
import com.gowiper.wallet.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;

public abstract class HttpGetThread extends Thread {
    private final AssetManager assets;
    private final String url;
    @CheckForNull
    private final String userAgent;

    private static final Logger log = LoggerFactory.getLogger(HttpGetThread.class);

    public HttpGetThread(@Nonnull final AssetManager assets, @Nonnull final String url, @Nullable final String userAgent) {
        this.assets = assets;
        this.url = url;
        this.userAgent = userAgent;
    }

    @Override
    public void run() {
        HttpURLConnection connection = null;

        log.debug("querying \"" + url + "\"...");

        try {
            connection = (HttpURLConnection) new URL(url).openConnection();

            if (connection instanceof HttpsURLConnection) {
                final InputStream keystoreInputStream = assets.open("ssl-keystore");

                final KeyStore keystore = KeyStore.getInstance("BKS");
                keystore.load(keystoreInputStream, "password".toCharArray());
                keystoreInputStream.close();

                final TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
                tmf.init(keystore);

                final SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, tmf.getTrustManagers(), null);

                ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
            }

            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.setReadTimeout(Constants.HTTP_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Charset", "utf-8");
            if (userAgent != null)
                connection.addRequestProperty("User-Agent", userAgent);
            connection.connect();

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                final long serverTime = connection.getDate();
                // TODO parse connection.getContentType() for charset

                final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charsets.UTF_8), 64);
                final String line = reader.readLine().trim();
                reader.close();

                handleLine(line, serverTime);
            }
        } catch (final Exception x) {
            handleException(x);
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    protected abstract void handleLine(@Nonnull String line, long serverTime);

    protected abstract void handleException(@Nonnull Exception x);
}
