package ru.spbstu.ghanalytics.core.http;

import java.time.Duration;

/**
 * "Wait for N": a pause made a dependency, so tests do not really sleep.
 * In tests pass {@code sleeps::add} (records the pause and returns at once);
 * in production use {@link #SYSTEM}.
 */
@FunctionalInterface
public interface Sleeper {

    /** Real sleep of the current thread. */
    Sleeper SYSTEM = Thread::sleep;

    /**
     * Pauses the current thread.
     *
     * @throws InterruptedException if the thread is interrupted: the caller must stop, not continue
     */
    void sleep(Duration duration) throws InterruptedException;
}
