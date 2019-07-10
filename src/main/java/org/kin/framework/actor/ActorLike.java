package org.kin.framework.actor;

import org.kin.framework.JvmCloseCleaner;
import org.kin.framework.actor.domain.ActorPath;
import org.kin.framework.concurrent.SimpleThreadFactory;
import org.kin.framework.concurrent.ThreadManager;
import org.kin.framework.utils.ExceptionUtils;
import org.kin.framework.utils.SysUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author huangjianqin
 * @date 2019/7/9
 */
public abstract class ActorLike<AL extends ActorLike<?>> implements Actor<AL>, Runnable {
    private static final Logger log = LoggerFactory.getLogger("actor");
    private static final Logger profileLog = LoggerFactory.getLogger("actorProfile");

    private static final ThreadManager THREADS = new ThreadManager(
            new ThreadPoolExecutor(0, SysUtils.getSuitableThreadNum() * 2 - 1,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    new SimpleThreadFactory("actorlike")),
            new ScheduledThreadPoolExecutor(SysUtils.getSuitableThreadNum() * 2 - 1,
                    new SimpleThreadFactory("actorlike-schedule")));
    private static Map<ActorLike<?>, Queue<Future>> futures = new ConcurrentHashMap<>();

    static {
        JvmCloseCleaner.DEFAULT().add(() -> {
            THREADS.shutdown();
        });
    }

    private ThreadManager threads;

    private final Queue<Message<AL>> messageBox = new LinkedBlockingDeque<>();
    private final AtomicInteger boxSize = new AtomicInteger();
    private volatile Thread currentThread;
    private volatile boolean isStopped = false;

    public ActorLike() {
        this(THREADS);
    }

    public ActorLike(ThreadManager threads) {
        this.threads = threads;
        //每1h清楚已结束的调度
        scheduleAtFixedRate(actor -> clearFinishedFutures(), 0, 1, TimeUnit.HOURS);
    }

    public ActorLike(ExecutorService executorService, ScheduledExecutorService scheduledExecutorService) {
        this(new ThreadManager(executorService, scheduledExecutorService));
    }


    @Override
    public <T> void receive(T message) {
        if (!isStopped) {
            if (message instanceof Message) {
                messageBox.add((Message<AL>) message);
                tryRun();
            } else {
                throw new UnsupportedOperationException();
            }
        }
    }

    @Override
    public void tell(Message<AL> message) {
        if (!isStopped) {
            messageBox.add(message);
            tryRun();
        }
    }

    @Override
    public Future<?> schedule(Message<AL> message, long delay, TimeUnit unit) {
        if (!isStopped) {
            Future future = threads.schedule(() -> {
                receive(message);
            }, delay, unit);
            addFuture(future);
            return future;
        }
        return null;
    }

    @Override
    public Future<?> scheduleAtFixedRate(Message<AL> message, long initialDelay, long period, TimeUnit unit) {
        if (!isStopped) {
            Future future = threads.scheduleAtFixedRate(() -> {
                receive(message);
            }, initialDelay, period, unit);
            addFuture(future);
            return future;
        }
        return null;
    }

    @Override
    public void stop() {
        stopNow();
    }

    @Override
    public void stopNow() {
        if (!isStopped) {
            isStopped = true;
            clearFutures();
        }
    }

    @Override
    public ActorPath getPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void run() {
        this.currentThread = Thread.currentThread();
        while (!isStopped && !this.currentThread.isInterrupted()) {
            Message message = messageBox.poll();
            if (message == null) {
                break;
            }

            long st = System.currentTimeMillis();
            try {
                message.handle(this);
            } catch (Exception e) {
                ExceptionUtils.log(e);
            }
            long cost = System.currentTimeMillis() - st;

            profileLog.info("handle mail({}) cost {} ms", message, cost);

            if (boxSize.decrementAndGet() <= 0) {
                break;
            }
        }
        this.currentThread = null;
    }

    private void tryRun() {
        if (!isStopped && boxSize.incrementAndGet() == 1) {
            threads.execute(this);
        }
    }

    private void addFuture(Future<?> future) {
        Queue<Future> queue;
        while ((queue = futures.putIfAbsent(this, new ConcurrentLinkedQueue<>())) == null) {
        }
        queue.add(future);
    }

    private void clearFutures() {
        Queue<Future> old = futures.remove(this);
        if (old != null) {
            for (Future future : old) {
                if (!future.isDone() || !future.isCancelled()) {
                    future.cancel(true);
                }
            }
        }
    }

    private void clearFinishedFutures() {
        Queue<Future> old = futures.get(this);
        if (old != null) {
            Iterator<Future> iterator = old.iterator();
            while (iterator.hasNext()) {
                Future future = iterator.next();
                if (future.isDone()) {
                    iterator.remove();
                }
            }
        }
    }
}
