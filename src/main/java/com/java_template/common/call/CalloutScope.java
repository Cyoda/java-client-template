package com.java_template.common.call;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * ABOUTME: The processor/criterion callout a thread is working on (spec §4.4). While open, EntityService
 * calls go out as M2M and join the callout's transaction with its tx-token; attribution follows the
 * transaction's origin. Threads the application starts itself lose the scope unless wrapped with
 * {@link #wrap}; their calls then go out unjoined as M2M. Under COMMIT_BEFORE_DISPATCH with
 * startNewTxOnDispatch=false the callout carries no token, so calls are plain M2M requests attributed
 * to the M2M account. A token lives for the try's answer limit plus CYODA_CALLOUT_PASS_ALLOWANCE (30 s).
 */
public final class CalloutScope implements AutoCloseable {

    private static final ThreadLocal<CalloutScope> CURRENT = new ThreadLocal<>();
    /** Marker scope used by {@link #unjoined}: M2M, no token, never ends. Never installed via {@link #open}. */
    private static final CalloutScope DETACHED = new CalloutScope(null, null, null);

    private final String txToken;
    private final CalloutScope previous;
    private final SecurityContext previousSecurityContext;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private CalloutScope(String txToken, CalloutScope previous, SecurityContext previousSecurityContext) {
        this.txToken = txToken;
        this.previous = previous;
        this.previousSecurityContext = previousSecurityContext;
    }

    /**
     * Opens a scope on this thread and clears its SecurityContext: compute never forwards a user credential.
     * The thread's previous SecurityContext is put back by {@link #close()}.
     */
    public static CalloutScope open(String txToken) {
        SecurityContext previousSecurityContext = SecurityContextHolder.getContext();
        SecurityContextHolder.clearContext();
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            throw new IllegalStateException("SecurityContext still holds an authentication inside a callout scope");
        }
        CalloutScope scope = new CalloutScope(txToken, CURRENT.get(), previousSecurityContext);
        CURRENT.set(scope);
        return scope;
    }

    public static Optional<CalloutScope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public String txToken() {
        return txToken;
    }

    public boolean isOpen() {
        return open.get();
    }

    /** The callout's answer is being sent: later calls through this scope fail locally. */
    public void end() {
        if (this != DETACHED) {
            open.set(false);
        }
    }

    @Override
    public void close() {
        end();
        if (CURRENT.get() == this) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            SecurityContextHolder.setContext(previousSecurityContext);
        }
    }

    public static Runnable wrap(Runnable task) {
        CalloutScope scope = CURRENT.get();
        return scope == null ? task : () -> runIn(scope, () -> {
            task.run();
            return null;
        });
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        CalloutScope scope = CURRENT.get();
        return scope == null ? task : () -> {
            CalloutScope before = CURRENT.get();
            CURRENT.set(scope);
            try {
                return task.call();
            } finally {
                restore(before);
            }
        };
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        CalloutScope scope = CURRENT.get();
        return scope == null ? task : () -> runIn(scope, task);
    }

    /**
     * Runs {@code body} as M2M without the tx-token, detaching it from the current callout's transaction
     * (the remedy for COMMIT_IN_JOINED_TRANSACTION). This only ever detaches a transaction: it never
     * downgrades a credential (spec §4.2, "the framework never silently downgrades to M2M"). Outside a
     * callout scope there is no transaction to detach from, so {@code body} runs unchanged, with whatever
     * credential {@link CyodaCallContexts#current()} would otherwise have produced.
     */
    public static <T> T unjoined(Supplier<T> body) {
        if (CURRENT.get() == null) {
            return body.get();
        }
        return runIn(DETACHED, body);
    }

    private static <T> T runIn(CalloutScope scope, Supplier<T> body) {
        CalloutScope before = CURRENT.get();
        CURRENT.set(scope);
        try {
            return body.get();
        } finally {
            restore(before);
        }
    }

    private static void restore(CalloutScope before) {
        if (before == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(before);
        }
    }
}
