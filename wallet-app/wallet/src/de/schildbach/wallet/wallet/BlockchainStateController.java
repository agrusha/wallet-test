package de.schildbach.wallet.wallet;

import com.google.common.util.concurrent.ListenableFuture;
import com.gowiper.utils.observers.Observable;
import com.gowiper.utils.observers.ObservableDelegate;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainState;
import lombok.Delegate;

public class BlockchainStateController implements Observable<BlockchainStateController>{
    @Delegate
    ObservableDelegate<BlockchainStateController, LoadingController<BlockchainStateLoader, BlockchainState>> observableDelegate =
            new ObservableDelegate<BlockchainStateController, LoadingController<BlockchainStateLoader, BlockchainState>>(this);

    private final LoadingController<BlockchainStateLoader, BlockchainState> loadingController;

    public BlockchainStateController(WalletClient client) {
        loadingController = new LoadingController<BlockchainStateLoader, BlockchainState>(client.getApplicationContext(),
              new BlockchainStateLoader(client.getBlockchainService(), client.getBackgroundExecutor()),
              BlockchainService.ACTION_BLOCKCHAIN_STATE);
    }

    public ListenableFuture<BlockchainState> loadBlockchainState() {
        return loadingController.loadData();
    }
}
