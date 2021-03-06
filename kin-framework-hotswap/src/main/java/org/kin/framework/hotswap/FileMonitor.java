package org.kin.framework.hotswap;

import org.kin.framework.Closeable;
import org.kin.framework.concurrent.ExecutionContext;
import org.kin.framework.hotswap.agent.JavaAgentHotswap;
import org.kin.framework.utils.ClassUtils;
import org.kin.framework.utils.ExceptionUtils;
import org.kin.framework.utils.SysUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author huangjianqin
 * @date 2018/2/1
 * 文件监听器   单例模式
 * 利用nio 新api监听文件变换
 * 该api底层本质上是监听了操作系统的文件系统触发的文件更改事件
 * <p>
 * 异步热加载文件 同步类热更新
 */
public class FileMonitor extends Thread implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(FileMonitor.class);
    private WatchService watchService;
    /** hash(file name) -> Reloadable 实例 */
    private Map<Integer, AbstractFileReloadable> monitorItems;
    /** 类热加载工厂 */
    private JavaAgentHotswap javaAgentHotswap = JavaAgentHotswap.instance();
    /** 分段锁 */
    private Object[] locks;
    /** 异步热加载文件 执行线程 */
    private ExecutionContext executionContext;
    private volatile boolean isStopped = false;

    public FileMonitor() {
        this("hotSwapFileMonitor", null);
    }

    public FileMonitor(String name) {
        this(name, null);
    }

    public FileMonitor(ExecutionContext executionContext) {
        this("hotSwapFileMonitor", executionContext);
    }

    public FileMonitor(String name, ExecutionContext executionContext) {
        super(name);
        this.executionContext = executionContext;
    }

    private void init() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();

        monitorItems = new HashMap<>();
        locks = new Object[5];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }

        if (executionContext == null) {
            //默认设置
            this.executionContext = ExecutionContext.elastic(0, SysUtils.CPU_NUM, "file-monitor");
        }

        //监听热更class存储目录
        Path classesPath = Paths.get(JavaAgentHotswap.getClasspath());
        classesPath.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

        monitorJVMClose();
    }

    @Override
    public synchronized void start() {
        try {
            init();
        } catch (IOException e) {
            ExceptionUtils.throwExt(e);
        }
        super.start();
    }

    @Override
    public void run() {
        log.info("file monitor start");
        while (!isStopped && !Thread.currentThread().isInterrupted()) {
            List<Path> changedClasses = new ArrayList<>();
            try {
                WatchKey key = watchService.take();
                //变化的路径
                Path parentPath = (Path) key.watchable();
                List<WatchEvent<?>> events = key.pollEvents();
                events.forEach(event -> {
                    //变化item的名字(文件名或者文件夹名)
                    String itemName = event.context().toString();
                    int hashKey = itemName.hashCode();
                    //真实路径
                    Path childPath = Paths.get(parentPath.toString(), itemName);
                    log.debug("'{}' changed", childPath.toString());
                    if (!Files.isDirectory(childPath)) {
                        //非文件夹
                        if (itemName.endsWith(ClassUtils.CLASS_SUFFIX)) {
                            //处理类热更新
                            changedClasses.add(childPath);
                        } else {
                            synchronized (getLock(hashKey)) {
                                //处理文件热更新
                                AbstractFileReloadable fileReloadable = monitorItems.get(hashKey);
                                if (fileReloadable != null) {
                                    executionContext.execute(() -> {
                                        try {
                                            long startTime = System.currentTimeMillis();
                                            try (InputStream is = new FileInputStream(childPath.toFile())) {
                                                fileReloadable.reload(is);
                                            }
                                            long endTime = System.currentTimeMillis();
                                            log.info("hotswap file '{}' finished, time cost {} ms", childPath.toString(), endTime - startTime);
                                        } catch (IOException e) {
                                            log.error("", e);
                                        }
                                    });
                                }
                            }
                        }
                    }
                });
                //重置状态，让key等待事件
                key.reset();
            } catch (InterruptedException e) {

            }

            if (changedClasses.size() > 0) {
                //类热更新
                executionContext.execute(() -> javaAgentHotswap.hotswap(changedClasses));
                HotFix.instance().fix();
            }
        }
        log.info("file monitor end");
    }

    /**
     * 获取分段锁
     */
    private Object getLock(int key) {
        return locks[key % locks.length];
    }

    public void shutdown() {
        if (!isStopped) {
            isStopped = true;
            try {
                watchService.close();
            } catch (IOException e) {
                ExceptionUtils.throwExt(e);
            }
            executionContext.shutdown();
            //help GC
            monitorItems = null;
//            hotswapFactory = null;
            locks = null;
            executionContext = null;

            //中断监控线程, 让本线程退出
            interrupt();
        }
    }

    //-----------------------------------------------------------------------------------------------------------------
    private void checkStatus() {
        if (isStopped) {
            throw new IllegalStateException("file monitor has shutdowned");
        }
    }

    public void monitorFile(String pathStr, AbstractFileReloadable fileReloadable) {
        checkStatus();
        Path path = Paths.get(pathStr);
        monitorFile(path, fileReloadable);
    }

    public void monitorFile(Path path, AbstractFileReloadable fileReloadable) {
        checkStatus();
        if (!Files.isDirectory(path)) {
            try {
                monitorFile0(path.getParent(), path.getFileName().toString(), fileReloadable);
            } catch (IOException e) {
                ExceptionUtils.throwExt(e);
            }
        } else {
            throw new IllegalStateException("monitor file dir error");
        }
    }

    /**
     * 监听文件变化
     */
    private void monitorFile0(Path file, String itemName, AbstractFileReloadable fileReloadable) throws IOException {
        file.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

        int key = itemName.hashCode();
        synchronized (getLock(key)) {
            monitorItems.put(key, fileReloadable);
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
