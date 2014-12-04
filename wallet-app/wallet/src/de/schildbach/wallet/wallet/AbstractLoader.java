package de.schildbach.wallet.wallet;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.Callable;

@RequiredArgsConstructor(suppressConstructorProperties = true)
public abstract class AbstractLoader<Type> {
    private final ListeningScheduledExecutorService backgroundExecutor;
    private final LoadTask loadTask = new LoadTask();

    public ListenableFuture<Type> loadData() {
        return backgroundExecutor.submit(loadTask);
    }

    abstract protected Type getData();

    private class LoadTask implements Callable<Type> {
        @Override
        public Type call() throws Exception {
            return getData();
        }
    }
}
