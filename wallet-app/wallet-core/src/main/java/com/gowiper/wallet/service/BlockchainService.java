package com.gowiper.wallet.service;

import org.bitcoinj.core.Peer;
import org.bitcoinj.core.StoredBlock;

import javax.annotation.CheckForNull;
import java.util.List;

public interface BlockchainService {
    public static final String ACTION_PEER_STATE = BlockchainService.class.getPackage().getName() + ".peer_state";
    public static final String ACTION_PEER_STATE_NUM_PEERS = "num_peers";

    public static final String ACTION_BLOCKCHAIN_STATE = BlockchainService.class.getPackage().getName() + ".blockchain_state";

    public static final String ACTION_CANCEL_COINS_RECEIVED = BlockchainService.class.getPackage().getName() + ".cancel_coins_received";
    public static final String ACTION_RESET_BLOCKCHAIN = BlockchainService.class.getPackage().getName() + ".reset_blockchain";
    public static final String ACTION_BROADCAST_TRANSACTION = BlockchainService.class.getPackage().getName() + ".broadcast_transaction";
    public static final String ACTION_BROADCAST_TRANSACTION_HASH = "hash";

    BlockchainState getBlockchainState();

    @CheckForNull
    List<Peer> getConnectedPeers();

    List<StoredBlock> getRecentBlocks(int maxBlocks);
}
