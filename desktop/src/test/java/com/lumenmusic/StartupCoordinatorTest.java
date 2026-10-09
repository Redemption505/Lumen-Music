package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StartupCoordinatorTest {
    @Test void slowStartupDoesNotBlockCallerAndConcurrentRequestsShareAttempt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch continueStartup = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        try (var startup = new StartupCoordinator<AutoCloseable>(() -> {
            starts.incrementAndGet();
            entered.countDown();
            assertTrue(continueStartup.await(3, TimeUnit.SECONDS));
            return released::countDown;
        })) {
            var ready = assertTimeoutPreemptively(Duration.ofSeconds(1), startup::start);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(ready.isDone());
            assertSame(ready, startup.start());
            continueStartup.countDown();
            assertNotNull(ready.get(3, TimeUnit.SECONDS));
            assertSame(ready, startup.start());
            assertEquals(1, starts.get());
        }
        assertTrue(released.await(3, TimeUnit.SECONDS));
    }

    @Test void failedStartupCanRetryAndOwnsOnlySuccessfulResource() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch released = new CountDownLatch(1);
        try (var startup = new StartupCoordinator<AutoCloseable>(() -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("fixture startup failure");
            return released::countDown;
        })) {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> startup.start().get(3, TimeUnit.SECONDS));
            assertNotNull(startup.start().get(3, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
        assertTrue(released.await(3, TimeUnit.SECONDS));
    }

    @Test void closeCancelsStartupAndReleasesResourceReturnedAfterInterruption() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        var startup = new StartupCoordinator<AutoCloseable>(() -> {
            entered.countDown();
            try { allowReturn.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException cancelled) {
                interrupted.countDown();
                // Simulate a native call that returns a resource after cancellation.
                // close() cancels the task and shuts down its executor; either can
                // interrupt this thread. Keep the simulated native return pending
                // through repeated cancellation, within the original three seconds.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (true) {
                    try {
                        assertTrue(allowReturn.await(Math.max(0, deadline - System.nanoTime()),
                                TimeUnit.NANOSECONDS));
                        break;
                    } catch (InterruptedException repeatedCancellation) { }
                }
                Thread.currentThread().interrupt();
            }
            return released::countDown;
        });
        try {
            var ready = startup.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), startup::close);
            assertTrue(ready.isCancelled());
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(startup.start().isCompletedExceptionally());
        } finally {
            allowReturn.countDown();
            startup.close();
        }
        assertTrue(released.await(3, TimeUnit.SECONDS));
    }

    @Test void closeDoesNotBlockWhileOwnedResourceCloses() throws Exception {
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        var startup = new StartupCoordinator<AutoCloseable>(() -> () -> {
            closing.countDown();
            try { assertTrue(allowClose.await(3, TimeUnit.SECONDS)); }
            finally { released.countDown(); }
        });
        try {
            startup.start().get(3, TimeUnit.SECONDS);
            assertTimeoutPreemptively(Duration.ofSeconds(1), startup::close);
            assertTrue(closing.await(3, TimeUnit.SECONDS));
        } finally {
            allowClose.countDown();
            startup.close();
        }
        assertTrue(released.await(3, TimeUnit.SECONDS));
    }
}
