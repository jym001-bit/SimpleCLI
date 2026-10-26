package com.paicli.util;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 任务运行期间终端输入的独占锁。
 *
 * <p>任务在后台线程执行时，主线程会轮询终端监听 ESC 取消；计划审阅、工具审批也要在后台线程读终端。
 * 两边同时读会互相抢按键：按 I 没反应、补充要求丢字、回车被 ESC 监听吞掉。所以交互读取前先拿这把锁，
 * ESC 监听拿不到锁就跳过本轮轮询，把终端让给正在进行的交互。
 */
public final class TerminalInputLock {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private TerminalInputLock() {
    }

    /** 交互读取：最多等 ESC 监听结束当前一次短轮询（约百毫秒），之后独占终端输入直到交互结束。 */
    public static <T> T exclusive(Supplier<T> interaction) {
        LOCK.lock();
        try {
            return interaction.get();
        } finally {
            LOCK.unlock();
        }
    }

    /** ESC 监听专用：返回 false 说明正有交互在读终端，本轮不要读。成功时必须调用 {@link #release()}。 */
    public static boolean tryAcquire() {
        return LOCK.tryLock();
    }

    public static void release() {
        LOCK.unlock();
    }
}
