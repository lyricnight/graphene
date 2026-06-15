package tytoo.grapheneui.internal.cef;

import tytoo.grapheneui.internal.platform.GraphenePlatform;

import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

final class GrapheneCefInitializationDispatcher {
    private final boolean dispatchToAwtEventThread;

    GrapheneCefInitializationDispatcher() {
        this(GraphenePlatform.isMac());
    }

    GrapheneCefInitializationDispatcher(boolean dispatchToAwtEventThread) {
        this.dispatchToAwtEventThread = dispatchToAwtEventThread;
    }

    boolean isDispatchThread() {
        return EventQueue.isDispatchThread();
    }

    <T> T dispatch(Callable<T> task) {
        Callable<T> validatedTask = Objects.requireNonNull(task, "task");
        if (!dispatchToAwtEventThread || isDispatchThread()) {
            return call(validatedTask);
        }

        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            EventQueue.invokeAndWait(() -> {
                try {
                    result.set(validatedTask.call());
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while dispatching CEF lifecycle work", exception);
        } catch (InvocationTargetException exception) {
            throw propagate(exception.getCause());
        }

        Throwable throwable = failure.get();
        if (throwable != null) {
            throw propagate(throwable);
        }

        return result.get();
    }

    void dispatch(Runnable task) {
        Runnable validatedTask = Objects.requireNonNull(task, "task");
        dispatch(() -> {
            validatedTask.run();
            return null;
        });
    }

    private static <T> T call(Callable<T> task) {
        try {
            return task.call();
        } catch (Throwable throwable) {
            throw propagate(throwable);
        }
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }

        if (throwable instanceof Error error) {
            throw error;
        }

        return new IllegalStateException("Failed to execute CEF lifecycle work", throwable);
    }
}
