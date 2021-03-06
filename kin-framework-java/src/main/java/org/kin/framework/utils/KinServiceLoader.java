package org.kin.framework.utils;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类spi机制, 与spring.factories加载类似, 内容是properties格式
 *
 * @author huangjianqin
 * @date 2020/9/27
 */
public class KinServiceLoader {
    /** 默认路径 */
    private static final String DEFAULT_FILE_NAME = "META-INF/kin.factories";

    /** The class loader used to locate, load, and instantiate providers */
    private final ClassLoader classLoader;
    /** The access control context taken when the ServiceLoader is created */
    private final AccessControlContext acc;
    /** key -> service class name || {@link SPI}注解的值, value -> service implement class name */
    private volatile Multimap<String, String> service2Implement;
    /** key -> service class, value -> service implement instance */
    private volatile Map<Class<?>, ServiceLoader<?>> service2Loader;

    private KinServiceLoader(String fileName, ClassLoader cl) {
        classLoader = (cl == null) ? ClassLoader.getSystemClassLoader() : cl;
        acc = (System.getSecurityManager() != null) ? AccessController.getContext() : null;
        reload(fileName);
    }

    //-------------------------------------------------------------------------------------------------------------------
    public static KinServiceLoader load() {
        return load(DEFAULT_FILE_NAME, Thread.currentThread().getContextClassLoader());
    }

    public static KinServiceLoader load(String fileName) {
        return load(fileName, Thread.currentThread().getContextClassLoader());
    }

    public static KinServiceLoader loadInstalled(String fileName) {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        ClassLoader prev = null;
        while (cl != null) {
            prev = cl;
            cl = cl.getParent();
        }
        return load(fileName, prev);
    }

    public static KinServiceLoader load(String fileName, ClassLoader loader) {
        return new KinServiceLoader(fileName, loader);
    }
    //-------------------------------------------------------------------------------------------------------------------

    /**
     * 重新加载
     */
    public synchronized void reload(String fileName) {
        service2Implement = LinkedListMultimap.create();
        service2Loader = new ConcurrentHashMap<>();

        Enumeration<URL> configs;
        try {
            if (classLoader == null) {
                configs = ClassLoader.getSystemResources(fileName);
            } else {
                configs = classLoader.getResources(fileName);
            }

            while (configs.hasMoreElements()) {
                URL url = configs.nextElement();
                parse(url);
            }
        } catch (Exception e) {
            ExceptionUtils.throwExt(e);
        }
    }

    /**
     * 解析配置文件
     */
    private void parse(URL url) {
        try {
            Properties properties = new Properties();
            try (InputStream is = url.openStream()) {
                properties.load(is);
                for (String serviceClassName : properties.stringPropertyNames()) {
                    String implementClassNames = properties.getProperty(serviceClassName);
                    HashSet<String> filtered = new HashSet<>(Arrays.asList(implementClassNames.split(",")));
                    service2Implement.putAll(serviceClassName, filtered);
                }
            }
        } catch (IOException e) {
            ExceptionUtils.throwExt(e);
        }
    }

    /**
     * 判断是否支持SPI机制
     */
    private void checkSupport(Class<?> serviceClass) {
        if (!serviceClass.isAnnotationPresent(SPI.class)) {
            throw new IllegalArgumentException(serviceClass.getCanonicalName().concat(" doesn't support spi, please ensure @SPI"));
        }
    }

    /**
     * 获取某接口的{@link ServiceLoader}的迭代器
     */
    @SuppressWarnings("unchecked")
    private synchronized <S> Iterator<S> iterator(Class<S> serviceClass) {
        checkSupport(serviceClass);
        //从接口名 或者 @SPI注解的提供的value 获取该接口实现类
        HashSet<String> filtered = new HashSet<>(service2Implement.get(serviceClass.getCanonicalName()));

        SPI spi = serviceClass.getAnnotation(SPI.class);
        if (Objects.nonNull(spi)) {
            String key = spi.key();
            if (StringUtils.isNotBlank(key)) {
                filtered.addAll(service2Implement.get(key));
            }
        }

        ServiceLoader<S> newLoader = new ServiceLoader<>(serviceClass, new ArrayList<>(filtered));
        ServiceLoader<?> loader = service2Loader.putIfAbsent(serviceClass, newLoader);
        if (Objects.isNull(loader)) {
            //本来没有值
            loader = newLoader;
        }
        return (Iterator<S>) loader.iterator();
    }

    /**
     * 获取合适的扩展service类
     */
    public synchronized <S> S getAdaptiveExtension(Class<S> serviceClass) {
        return getAdaptiveExtension(serviceClass, () -> null);
    }

    /**
     * 获取合适的扩展service类
     */
    public synchronized <S> S getAdaptiveExtension(Class<S> serviceClass, Class<S> defaultServiceClass) {
        return getAdaptiveExtension(serviceClass, () -> ClassUtils.instance(defaultServiceClass));
    }

    /**
     * 获取合适的扩展service类
     */
    private <S> S getAdaptiveExtension(Class<S> serviceClass, Callable<S> serviceGetter) {
        checkSupport(serviceClass);
        String defaultServiceName = "";
        SPI spi = serviceClass.getAnnotation(SPI.class);
        if (Objects.nonNull(spi)) {
            defaultServiceName = spi.value();
        }

        Iterator<S> serviceIterator = iterator(serviceClass);
        while (serviceIterator.hasNext()) {
            S implService = serviceIterator.next();
            String implServiceSimpleName = implService.getClass().getSimpleName();
            if (StringUtils.isNotBlank(defaultServiceName) &&
                    //扩展service class name |
                    (defaultServiceName.equalsIgnoreCase(implService.getClass().getCanonicalName()) ||
                            //service simple class name |
                            defaultServiceName.equalsIgnoreCase(implServiceSimpleName) ||
                            //前缀 + service simple class name |
                            defaultServiceName.concat(serviceClass.getSimpleName()).equalsIgnoreCase(implServiceSimpleName))) {
                return implService;
            }
        }

        //找不到任何符合的扩展service类, return 默认
        if (StringUtils.isBlank(defaultServiceName) && Objects.nonNull(serviceGetter)) {
            try {
                return serviceGetter.call();
            } catch (Exception e) {
                ExceptionUtils.throwExt(e);
            }
        }

        throw new IllegalStateException("can not find Adaptive service for" + serviceClass.getCanonicalName());
    }

    /**
     * 获取所有扩展service类
     */
    public synchronized <S> List<S> getExtensions(Class<S> serviceClass) {
        checkSupport(serviceClass);

        List<S> services = new ArrayList<>();
        Iterator<S> serviceIterator = iterator(serviceClass);
        while (serviceIterator.hasNext()) {
            S implService = serviceIterator.next();
            services.add(implService);
        }

        return services;
    }

    //-------------------------------------------------------------------------------------------------------------------
    private class ServiceLoader<S> implements Iterable<S> {
        /** 接口类 */
        private final Class<S> service;
        /** lazy 加载 */
        private final LazyIterator lookupIterator;
        /** service cache */
        private final LinkedHashMap<String, S> providers = new LinkedHashMap<>();

        private ServiceLoader(Class<S> service, List<String> source) {
            this.service = service;
            this.lookupIterator = new LazyIterator(source);
        }

        @Override
        public Iterator<S> iterator() {
            return new Iterator<S>() {
                final Iterator<Map.Entry<String, S>> knownProviders
                        = providers.entrySet().iterator();

                @Override
                public boolean hasNext() {
                    if (knownProviders.hasNext()) {
                        return true;
                    }
                    return lookupIterator.hasNext();
                }

                @Override
                public S next() {
                    if (knownProviders.hasNext()) {
                        return knownProviders.next().getValue();
                    }
                    return lookupIterator.next();
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        //----------------------------------------------------------------------------------------------------------------

        /**
         * lazy加载接口实现类的迭代器
         */
        private class LazyIterator implements Iterator<S> {
            /** iterator 当前下标 */
            private int index;
            /** 接口实现类名 */
            private final List<String> source;

            public LazyIterator(List<String> source) {
                this.source = source;
            }

            /**
             * @return 是否可以继续迭代
             */
            private boolean hasNextService() {
                return index < source.size();
            }

            /**
             * 下一接口实现类
             */
            private S nextService() {
                if (!hasNextService()) {
                    throw new NoSuchElementException();
                }
                String cn = source.get(index);
                Class<?> c;
                try {
                    c = Class.forName(cn, false, classLoader);
                } catch (ClassNotFoundException x) {
                    throw new ServiceConfigurationError(String.format("%s: Provider %s not found", service.getCanonicalName(), cn));
                }

                if (!service.isAssignableFrom(c)) {
                    throw new ServiceConfigurationError(String.format("%s: Provider %s not a subtype", service.getCanonicalName(), cn));
                }
                try {
                    S p = service.cast(c.newInstance());
                    providers.put(cn, p);
                    index++;
                    return p;
                } catch (Throwable x) {
                    throw new ServiceConfigurationError(String.format("%s: Provider %s could not be instantiated", service.getCanonicalName(), cn), x);
                }
            }

            @Override
            public boolean hasNext() {
                if (acc == null) {
                    return hasNextService();
                } else {
                    PrivilegedAction<Boolean> action = this::hasNextService;
                    return AccessController.doPrivileged(action, acc);
                }
            }

            @Override
            public S next() {
                if (acc == null) {
                    return nextService();
                } else {
                    PrivilegedAction<S> action = this::nextService;
                    return AccessController.doPrivileged(action, acc);
                }
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

        }
    }
}
