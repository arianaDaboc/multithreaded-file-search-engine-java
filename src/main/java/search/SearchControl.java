package search;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Coordinates cancellation and lightweight progress updates for one search run. */
public final class SearchControl {
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private final Consumer<SearchProgress> progressListener;
    private volatile Thread coordinator;

    public SearchControl() {
        this(progress -> { });
    }

    public SearchControl(Consumer<SearchProgress> progressListener) {
        this.progressListener = progressListener == null ? progress -> { } : progressListener;
    }

    public boolean isCancellationRequested() {
        return cancellationRequested.get();
    }

    /** Requests cancellation and wakes the coordinator if it is waiting for a worker. */
    public void cancel() {
        if (cancellationRequested.compareAndSet(false, true)) {
            Thread runningCoordinator = coordinator;
            if (runningCoordinator != null && runningCoordinator != Thread.currentThread()) {
                runningCoordinator.interrupt();
            }
        }
    }

    void bindCoordinator(Thread thread) {
        coordinator = thread;
    }

    void reportProgress(int filesDiscovered, int filesScanned) {
        try {
            progressListener.accept(new SearchProgress(filesDiscovered, filesScanned));
        } catch (RuntimeException ignored) {
            // UI/reporting callbacks must not fail the search itself.
        }
    }

    public record SearchProgress(int filesDiscovered, int filesScanned) { }
}
