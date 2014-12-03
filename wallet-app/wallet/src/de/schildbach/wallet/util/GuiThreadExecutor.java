package de.schildbach.wallet.util;

import android.os.Handler;
import android.os.Looper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.gowiper.utils.Utils;
import edu.umd.cs.findbugs.annotations.NonNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Validate;

import java.util.concurrent.*;

public class GuiThreadExecutor implements Executor {
    @Getter
    private static final GuiThreadExecutor instance = new GuiThreadExecutor();


    @Getter private final Handler handler;
    @Getter private final Thread thread;

    private GuiThreadExecutor() {
        this(Looper.getMainLooper());
    }

    public GuiThreadExecutor(Looper looper) {
        this(new Handler(looper));
    }

    public GuiThreadExecutor(Handler handler) {
        this.handler = Validate.notNull(handler);
        this.thread = handler.getLooper().getThread();
    }

    @Override
    public void execute(@NonNull Runnable command) {
        Runnable task = command;
        if (Thread.currentThread() == this.thread) {
            task.run();
        } else {
            handler.post(task);
        }
    }

    public void executeSingle(Runnable runnable) {
        handler.removeCallbacks(runnable);
        execute(runnable);
    }

    public void executeLater(Runnable command) {
        handler.post(command);
    }

    public void executeLaterOneSecond(Runnable command) {
        executeLater(command, 1, TimeUnit.SECONDS);
    }

    public void executeLater(Runnable command, long time, TimeUnit timeUnit) {
        handler.postDelayed(command, timeUnit.toMillis(time));
    }

    public <T> ListenableFuture<T> submit(@NonNull Callable<T> task) {
        ListenableFutureTask<T> futureTask = ListenableFutureTask.create(task);
        execute(futureTask);
        return futureTask;
    }

    public <T> ListenableFuture<T> submit(@NonNull Runnable task, T result) {
        ListenableFutureTask<T> futureTask = ListenableFutureTask.create(task, result);
        execute(futureTask);
        return futureTask;
    }

    public ScheduledFuture<?> schedule(@NonNull Runnable task, long time, TimeUnit timeUnit) {
        return schedule(task, Utils.VOID, time, timeUnit);
    }

    public <T> ScheduledFuture<T> schedule(@NonNull Runnable task, T result, long time, TimeUnit timeUnit) {
        return scheduleTask(ListenableFutureTask.create(task, result), time, timeUnit);
    }

    public <T> ScheduledFuture<T> schedule(@NonNull final Callable<T> task, long time, TimeUnit timeUnit) {
        return scheduleTask(ListenableFutureTask.create(task), time, timeUnit);
    }

    private <T> ScheduledFuture<T> scheduleTask(ListenableFutureTask<T> futureTask, long time, TimeUnit timeUnit) {
        executeLater(futureTask, time, timeUnit);
        return createScheduledFuture(futureTask, time, timeUnit);
    }

    private <T> GuiScheduledFuture<T> createScheduledFuture(ListenableFutureTask<T> task,
                                                            long time, TimeUnit timeUnit) {
        return new GuiScheduledFuture<T>(task, time, timeUnit);
    }

    @RequiredArgsConstructor(suppressConstructorProperties = true)
    private class GuiScheduledFuture<T> implements ScheduledFuture<T> {
        private final ListenableFutureTask<T> task;
        private final long time;
        private final TimeUnit timeUnit;

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(time, timeUnit);
        }

        @Override
        public int compareTo(Delayed another) {
            if (this == another) {
                return 0;
            } else {
                TimeUnit compareUnit = TimeUnit.MILLISECONDS;
                return Utils.compareLong(getDelay(compareUnit), another.getDelay(compareUnit));
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!task.isCancelled()) {
                handler.removeCallbacks(task);
                task.cancel(mayInterruptIfRunning);
            }
            return isCancelled();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }

        @Override
        public boolean isDone() {
            return task.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return task.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return task.get(timeout, unit);
        }
    }
}

