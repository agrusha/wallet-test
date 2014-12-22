package com.gowiper.wallet.loaders;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.gowiper.wallet.service.BlockchainService;
import com.gowiper.wallet.data.BlockchainState;

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
