package de.schildbach.wallet.wallet;

import android.content.SharedPreferences;
import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableDelegate;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.ExchangeRate;
import lombok.Delegate;

import java.util.Map;

public class ExchangeRatesController implements Observable<ExchangeRatesController>{
    @Delegate
    ObservableDelegate<ExchangeRatesController, LoadingController<ExchangeRatesLoader, Map<String, ExchangeRate>>> observableDelegate =
            new ObservableDelegate<ExchangeRatesController, LoadingController<ExchangeRatesLoader, Map<String, ExchangeRate>>>(this);

    private final LoadingController<ExchangeRatesLoader, Map<String, ExchangeRate>> loadingController;

    public ExchangeRatesController(WalletClient client) {
        this.loadingController = new LoadingController<ExchangeRatesLoader, Map<String, ExchangeRate>>(
                client.getApplicationContext(),
                new ExchangeRatesLoader(client),
                null);
        this.loadingController.addObserver(observableDelegate);

        Configuration configuration = client.getConfiguration();
        configuration.registerOnSharedPreferenceChangeListener(new PrefsCurrencyChangeListener());
    }

    public ListenableFuture<Map<String, ExchangeRate>> loadExchangeRate() {
        return loadingController.loadData();
    }

    private class PrefsCurrencyChangeListener implements SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if(Configuration.PREFS_KEY_EXCHANGE_CURRENCY.equals(key)) {
                loadingController.load();
            }
        }
    }
}
