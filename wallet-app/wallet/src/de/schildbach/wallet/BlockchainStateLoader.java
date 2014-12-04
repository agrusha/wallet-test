package de.schildbach.wallet;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import de.schildbach.wallet.service.BlockchainService;
import de.schildbach.wallet.service.BlockchainState;

public class BlockchainStateLoader extends AbstractLoader<BlockchainState> {
    private final BlockchainService service;

    public BlockchainStateLoader(BlockchainService service, ListeningScheduledExecutorService backgroundExecutor) {
        super(backgroundExecutor);
        this.service = service;
    }

    @Override
    protected BlockchainState getData() {
        return service.getBlockchainState();
    }
}
