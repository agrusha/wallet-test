package de.schildbach.wallet.wallet;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import de.schildbach.wallet.service.BlockchainState;
import org.bitcoinj.core.Coin;

import java.util.Map;

// aggregates balance manager, blockchain state manager and exchange currency manager
public interface BlockchainManager extends Observable<BlockchainManager>{
    public ListenableFuture<Coin> loadBalance();
    public ListenableFuture<BlockchainState> loadBlockchainState();
    public ListenableFuture<Map<String, ExchangeRatesLoader.ExchangeRate>> loadExchangeRate();

    public BalanceController getBalanceController();
    public BlockchainStateController getBlockchainStateController();
    public ExchangeRatesController getExchangeRatesController();
}
