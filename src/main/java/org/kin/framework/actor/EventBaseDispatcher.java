package org.kin.framework.actor;

import org.kin.framework.concurrent.ExecutionContext;
import org.kin.framework.utils.ExceptionUtils;
import org.kin.framework.utils.SysUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author huangjianqin
 * @date 2020-04-15
 * <p>
 * 尽量不要blocking
 */
public class EventBaseDispatcher<KEY, MSG> extends AbstractDispatcher<KEY, MSG> {
    private static final Logger log = LoggerFactory.getLogger(EventBaseDispatcher.class);
    private final ReceiverData<MSG> POISON_PILL = new ReceiverData<>(null, false);

    private int parallelism;
    private Map<KEY, ReceiverData<MSG>> receiverDatas = new ConcurrentHashMap<>();
    private LinkedBlockingQueue<ReceiverData<MSG>> pendingDatas = new LinkedBlockingQueue<>();

    public EventBaseDispatcher(int parallelism) {
        super(ExecutionContext.forkjoin(
                parallelism, "eventBase-dispatcher",
                SysUtils.getSuitableThreadNum() / 2 + 1, "eventBase-dispatcher-schedule"));
        this.parallelism = parallelism;
    }

    @Override
    public void doInit() {
        for (int i = 0; i < parallelism; i++) {
            executionContext.execute(new MessageLoop());
        }
    }

    @Override
    public void doRegister(KEY key, Receiver<MSG> receiver, boolean enableConcurrent) {
        synchronized (this) {
            if (stopped) {
                throw new IllegalStateException("eventBase-dispatcher has been stopped");
            }

            if (Objects.nonNull(receiverDatas.putIfAbsent(key, new ReceiverData<>(receiver, enableConcurrent)))) {
                throw new IllegalArgumentException(String.format("%s has registried", key));
            }

            ReceiverData<MSG> data = receiverDatas.get(key);
            pendingDatas.offer(data);
        }
    }

    @Override
    public void doUnRegister(KEY key) {
        ReceiverData<MSG> data = receiverDatas.remove(key);
        if (Objects.nonNull(data)) {
            data.inBox.close();
            pendingDatas.offer(data);
        }
    }

    @Override
    public void doPostMessage(KEY key, MSG message) {
        synchronized (this) {
            if (stopped) {
                return;
            }

            ReceiverData<MSG> data = receiverDatas.get(key);
            if (Objects.nonNull(data)) {
                data.inBox.post(new OnMessageSignal<>(message));
                pendingDatas.offer(data);
            }
        }
    }

    @Override
    public void doClose() {
        synchronized (this) {
            if (stopped) {
                return;
            }

            stopped = true;
        }

        receiverDatas.keySet().forEach(this::unRegister);
        pendingDatas.offer(POISON_PILL);
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
                    data.inBox.process(EventBaseDispatcher.this);
                }
            } catch (InterruptedException e) {

            } catch (Exception e) {
                ExceptionUtils.log(e);
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