package io.github.domsimus.keychain.core.auth;

import java.util.ArrayList;
import java.util.List;

/** Cooperative cancellation for a sign-in. Cancel actions run once, on the cancelling thread. */
public final class Cancellation {
    private final List<Runnable> actions = new ArrayList<>();
    private boolean cancelled;

    public void cancel() {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            toRun = List.copyOf(actions);
            actions.clear();
        }
        toRun.forEach(Runnable::run);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Registers cleanup that runs on cancel; runs it immediately if already cancelled. */
    public void onCancel(Runnable action) {
        synchronized (this) {
            if (!cancelled) {
                actions.add(action);
                return;
            }
        }
        action.run();
    }

    void throwIfCancelled() throws AuthException {
        if (isCancelled()) throw new AuthException(AuthException.Reason.CANCELLED, "Sign-in cancelled");
    }
}
