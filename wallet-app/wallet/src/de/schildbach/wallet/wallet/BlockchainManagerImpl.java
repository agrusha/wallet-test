package de.schildbach.wallet.wallet;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.ObservableDelegate;
import de.schildbach.wallet.ExchangeRate;
import de.schildbach.wallet.service.BlockchainState;
import lombok.Delegate;
import lombok.Getter;
import org.bitcoinj.core.Coin;

import java.util.Map;

public class BlockchainManagerImpl implements BlockchainManager{
    @Delegate
    ObservableDelegate<BlockchainManager, Object> observableDelegate =
            new ObservableDelegate<BlockchainManager, Object>(this);

    @Getter
    private final BalanceController balanceController;
    @Getter
    private final BlockchainStateController blockchainStateController;
    @Getter
    private final ExchangeRatesController exchangeRatesController;

    public BlockchainManagerImpl(WalletClient client) {
        this.balanceController = new BalanceController(client);
        this.blockchainStateController = new BlockchainStateController(client);
        this.exchangeRatesController = new ExchangeRatesController(client);

        balanceController.addObserver(observableDelegate);
        blockchainStateController.addObserver(observableDelegate);
        exchangeRatesController.addObserver(observableDelegate);
    }


    @Override
    public ListenableFuture<Coin> loadBalance() {
        return balanceController.loadBalance();
    }

    @Override
    public ListenableFuture<BlockchainState> loadBlockchainState() {
        return blockchainStateController.loadBlockchainState();
    }

    @Override
    public ListenableFuture<Map<String, ExchangeRate>> loadExchangeRate() {
        return exchangeRatesController.loadExchangeRate();
    }
}
