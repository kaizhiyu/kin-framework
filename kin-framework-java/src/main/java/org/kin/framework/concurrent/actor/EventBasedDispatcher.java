package org.kin.framework.concurrent.actor;

import org.kin.framework.concurrent.ExecutionContext;
import org.kin.framework.utils.SysUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 底层消息处理实现是基于事件处理
 * 尽量不要blocking
 *
 * @author huangjianqin
 * @date 2020-04-15
 * <p>
 */
public class EventBasedDispatcher<KEY, MSG> extends AbstractDispatcher<KEY, MSG> {
    private static final Logger log = LoggerFactory.getLogger(EventBasedDispatcher.class);
    /** 毒药, 终止message loop */
    private final ReceiverData<MSG> POISON_PILL = new ReceiverData<>(null, false);

    /** 并发数 */
    private int parallelism;
    /** Receiver数据 */
    private Map<KEY, ReceiverData<MSG>> receiverDatas = new ConcurrentHashMap<>();
    /** 等待数据处理的receivers */
    //TODO 考虑增加标志位, 在线程安全模式下, 如果Receiver消息正在被处理, 则不需要入队, 减少队列长度, 但这样子就会存在'比较忙'的Receiver长期占用, 其他Receiver消息得不到处理的问题
    private LinkedBlockingQueue<ReceiverData<MSG>> pendingDatas = new LinkedBlockingQueue<>();
    /** 是否已启动message loop */
    private boolean isMessageLoopRun;

    public EventBasedDispatcher(int parallelism) {
        super(ExecutionContext.forkjoin(
                parallelism, "eventBasedDispatcher",
                SysUtils.CPU_NUM / 2 + 1, "eventBasedDispatcher-scheduler"));
        this.parallelism = parallelism;
    }

    @Override
    protected void doRegister(KEY key, Receiver<MSG> receiver, boolean enableConcurrent) {
        if (Objects.nonNull(receiverDatas.putIfAbsent(key, new ReceiverData<>(receiver, enableConcurrent)))) {
            throw new IllegalArgumentException(String.format("%s has registried", key));
        }

        ReceiverData<MSG> data = receiverDatas.get(key);
        pendingDatas.offer(data);

        //lazy init
        if (!isMessageLoopRun) {
            for (int i = 0; i < parallelism; i++) {
                executionContext.execute(new MessageLoop());
            }
            isMessageLoopRun = true;
        }
    }

    @Override
    protected void doUnRegister(KEY key) {
        ReceiverData<MSG> data = receiverDatas.remove(key);
        if (Objects.nonNull(data)) {
            data.inBox.close();
            pendingDatas.offer(data);
        }
    }

    @Override
    protected void doPostMessage(KEY key, MSG message) {
        ReceiverData<MSG> data = receiverDatas.get(key);
        if (Objects.nonNull(data)) {
            data.inBox.post(new InBox.OnMessageSignal<>(message));
            pendingDatas.offer(data);
        }
    }

    @Override
    protected void doPost2All(MSG message) {
        for (KEY key : receiverDatas.keySet()) {
            doPostMessage(key, message);
        }
    }

    @Override
    protected void doClose() {
        receiverDatas.keySet().forEach(this::unregister);
        pendingDatas.offer(POISON_PILL);
    }

    @Override
    public boolean isRegistered(KEY key) {
        return receiverDatas.containsKey(key);
    }

    private class MessageLoop implements Runnable {
        @Override
        public void run() {
            try {
                while (true) {
                    ReceiverData<MSG> data = pendingDatas.take();
                    if (data == POISON_PILL) {
                        pendingDatas.offer(POISON_PILL);
                        return;
                    }
                    data.inBox.process(EventBasedDispatcher.this);
                }
            } catch (InterruptedException e) {

            } catch (Exception e) {
                log.error("", e);
                try {
                    //re-run
                    executionContext.execute(new MessageLoop());
                } finally {
                    throw e;
                }
            }
        }
    }

    private static class ReceiverData<MSG> {
        private InBox<MSG> inBox;

        private ReceiverData(Receiver<MSG> receiver, boolean enableConcurrent) {
            inBox = new InBox<>(receiver, enableConcurrent);
        }
    }
}
