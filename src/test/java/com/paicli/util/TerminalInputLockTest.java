package com.paicli.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalInputLockTest {

    @Test
    void escWatcherSkipsWhileInteractionOwnsInput() throws Exception {
        CountDownLatch interacting = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<String> interaction = CompletableFuture.supplyAsync(() ->
                TerminalInputLock.exclusive(() -> {
                    interacting.countDown();
                    await(finish);
                    return "done";
                }));

        assertTrue(interacting.await(5, TimeUnit.SECONDS));
        assertFalse(TerminalInputLock.tryAcquire(), "审阅/审批读终端时 ESC 监听不能再读");

        finish.countDown();
        assertEquals("done", interaction.get(5, TimeUnit.SECONDS));
        assertTrue(TerminalInputLock.tryAcquire(), "交互结束后 ESC 监听恢复");
        TerminalInputLock.release();
    }

    @Test
    void interactionWaitsForInFlightEscPoll() throws Exception {
        assertTrue(TerminalInputLock.tryAcquire());
        CompletableFuture<String> interaction = CompletableFuture.supplyAsync(() ->
                TerminalInputLock.exclusive(() -> "review"));
        try {
            Thread.sleep(100);
            assertFalse(interaction.isDone(), "ESC 监听这一轮没结束前，交互不能开始读终端");
        } finally {
            TerminalInputLock.release();
        }
        assertEquals("review", interaction.get(5, TimeUnit.SECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
