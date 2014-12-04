package de.schildbach.wallet;

import com.gowiper.utils.observers.ObservableDelegate;
import lombok.Delegate;

public class BlockchainManagerImpl implements BlockchainManager{
    @Delegate
    ObservableDelegate<BlockchainManager, Object> observableDelegate =
            new ObservableDelegate<BlockchainManager, Object>(this);


}
