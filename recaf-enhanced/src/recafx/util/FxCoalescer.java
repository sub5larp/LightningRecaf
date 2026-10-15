package recafx.util;

import java.util.concurrent.atomic.AtomicBoolean;
import software.coley.recaf.util.FxThreadUtil;

/** Keeps at most one pending UI refresh while background work changes many classes. */
public final class FxCoalescer implements Runnable {
    private final AtomicBoolean pending = new AtomicBoolean();
    private final Runnable action;

    public FxCoalescer(Runnable action) { this.action = action; }

    @Override
    public void run() {
        if (pending.compareAndSet(false, true)) FxThreadUtil.run(() -> {
            pending.set(false);
            action.run();
        });
    }
}
