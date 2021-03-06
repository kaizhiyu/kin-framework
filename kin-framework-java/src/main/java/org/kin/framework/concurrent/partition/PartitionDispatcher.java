package org.kin.framework.concurrent.partition;

import com.google.common.base.Preconditions;
import org.kin.framework.concurrent.ExecutionContext;
import org.kin.framework.concurrent.actor.Dispatcher;
import org.kin.framework.concurrent.actor.PinnedDispatcher;
import org.kin.framework.concurrent.actor.Receiver;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author huangjianqin
 * @date 2017/10/26
 * <p>
 * 有限分区Dispatcher
 * 利用Message的某种属性将Message分区,从而达到同一类的Message按顺序在同一线程执行
 */
public abstract class PartitionDispatcher<KEY, MSG> implements Dispatcher<KEY, MSG> {
    protected Dispatcher<Integer, MSG> pinnedDispatcher;
    /** 分区数 */
    protected final int partitionNum;
    /** 分区算法 */
    protected final Partitioner<KEY> partitioner;
    /** 已注册的key */
    private final Set<Integer> registeredKeies;

    public PartitionDispatcher() {
        this(5);
    }

    public PartitionDispatcher(int partitionNum) {
        this(partitionNum, HashPartitioner.INSTANCE);
    }

    public PartitionDispatcher(int partitionNum, Partitioner<KEY> partitioner) {
        this(partitionNum, partitioner, "partition-task-dispatcher-");
    }

    public PartitionDispatcher(int partitionNum, Partitioner<KEY> partitioner, String workerNamePrefix) {
        Preconditions.checkArgument(partitionNum > 0, "partitionNum field must be greater then 0");

        this.pinnedDispatcher = new PinnedDispatcher<>(partitionNum, workerNamePrefix);
        this.partitionNum = partitionNum;
        this.partitioner = partitioner;
        this.registeredKeies = new HashSet<>();
    }

    //------------------------------------------------------------------------------------------------------------------
    @Override
    public final void register(KEY key, Receiver<MSG> receiver, boolean enableConcurrent) {
        int realKey = getRealKey(key);
        if (registeredKeies.size() != partitionNum) {
            //保证每个分区仅仅注册一次
            synchronized (registeredKeies) {
                if (registeredKeies.size() != partitionNum && registeredKeies.add(realKey)) {
                    pinnedDispatcher.register(realKey, receiver(), false);
                }
            }
        }
    }

    @Override
    public final void unregister(KEY key) {
        int realKey = getRealKey(key);
        if (registeredKeies.contains(realKey)) {
            pinnedDispatcher.unregister(realKey);
        }
    }

    @Override
    public boolean isRegistered(KEY key) {
        int realKey = getRealKey(key);
        return pinnedDispatcher.isRegistered(realKey);
    }

    @Override
    public final void postMessage(KEY key, MSG message) {
        register(key, null, false);
        pinnedDispatcher.postMessage(getRealKey(key), message);
    }

    @Override
    public void post2All(MSG message) {
        throw new UnsupportedOperationException("Partition Dispatch doesn't support post to all receviers");
    }

    @Override
    public void schedule(KEY key, MSG message, long delay, TimeUnit unit) {
        executionContext().schedule(() -> postMessage(key, message), delay, unit);
    }

    @Override
    public void scheduleAtFixedRate(KEY key, MSG message, long initialDelay, long period, TimeUnit unit) {
        executionContext().scheduleAtFixedRate(() -> postMessage(key, message), initialDelay, period, unit);
    }

    @Override
    public void scheduleWithFixedDelay(KEY key, MSG message, long initialDelay, long delay, TimeUnit unit) {
        executionContext().scheduleWithFixedDelay(() -> postMessage(key, message), initialDelay, delay, unit);
    }

    @Override
    public final void shutdown() {
        close();
    }

    @Override
    public void close() {
        pinnedDispatcher.close();
    }

    @Override
    public final ExecutionContext executionContext() {
        return pinnedDispatcher.executionContext();
    }

    //------------------------------------------------------------------------------------------------------------------

    /**
     * @return 返回处理特定消息的Receiver
     */
    protected abstract Receiver<MSG> receiver();

    /**
     * 根据分区算法计算分区id
     */
    private int getRealKey(KEY key) {
        return partitioner.toPartition(key, partitionNum);
    }

    /**
     * 给特定分区id注册Receiver
     */
    protected void register(KEY key) {
        register(key, null, false);
    }
}
