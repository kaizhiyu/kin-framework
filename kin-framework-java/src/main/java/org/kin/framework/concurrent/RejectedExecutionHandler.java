package org.kin.framework.concurrent;

/**
 * {@link SingleThreadScheduledExecutor} 拒绝执行task处理器
 *
 * @author huangjianqin
 * @date 2020/11/23
 */
public interface RejectedExecutionHandler {
    /** do nothing handler */
    RejectedExecutionHandler EMPTY = (task, executor) -> {
    };

    /**
     * 拒绝执行task处理器
     *
     * @param task     具体任务
     * @param executor 哪个executor
     */
    void rejected(Runnable task, SingleThreadScheduledExecutor executor);
}