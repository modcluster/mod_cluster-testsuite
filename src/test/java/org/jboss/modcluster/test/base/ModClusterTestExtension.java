package org.jboss.modcluster.test.base;

import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit 5 Extension for mod_cluster test lifecycle management.
 * Provides dependency injection for test infrastructure.
 */
public class ModClusterTestExtension implements BeforeEachCallback, AfterEachCallback,
        ParameterResolver, TestInstancePostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ModClusterTestExtension.class);

    private static final String BALANCER_KEY = "balancer";
    private static final String WORKER1_KEY = "worker1";
    private static final String WORKER2_KEY = "worker2";
    private static final String WORKER3_KEY = "worker3";
    private static final String WORKER4_KEY = "worker4";
    private static final String HTTP_CLIENT_KEY = "httpClient";

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("=== Starting test: {} ===", context.getDisplayName());

        BalancerType balancerType = BalancerType.fromSystemProperty();
        log.info("Balancer type: {}", balancerType);

        ExtensionContext.Store store = getStore(context);

        // Create balancer and store BEFORE start — so afterEach can clean up network even if start fails
        Balancer balancer = Balancer.create(balancerType);
        if (context.getRequiredTestClass().isAnnotationPresent(SkipModProxyCluster.class)) {
            balancer.setSkipModProxyCluster(true);
        }
        store.put(BALANCER_KEY, balancer);
        balancer.start();

        // Create HTTP client
        store.put(HTTP_CLIENT_KEY, new HttpClient());

        log.info("Balancer started: {}", balancer.getHttpUrl());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);

        // Stop workers if started
        for (String workerKey : new String[]{WORKER1_KEY, WORKER2_KEY, WORKER3_KEY, WORKER4_KEY}) {
            WildFlyWorker worker = store.get(workerKey, WildFlyWorker.class);
            if (worker != null) {
                try {
                    worker.stop();
                } catch (Exception e) {
                    log.debug("Ignoring error stopping {}: {}", workerKey, e.getMessage());
                }
            }
        }

        // Stop balancer (also closes the per-test network if it owns it)
        Balancer balancer = store.get(BALANCER_KEY, Balancer.class);
        if (balancer != null) {
            try {
                balancer.stop();
            } catch (Exception e) {
                log.debug("Ignoring error stopping balancer: {}", e.getMessage());
            }
        }

        log.info("=== Finished test: {} ===", context.getDisplayName());
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == TestCluster.class ||
               type == Balancer.class ||
               type == HttpClient.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        ExtensionContext.Store store = getStore(extensionContext);

        if (type == TestCluster.class) {
            return new TestCluster(store);
        } else if (type == Balancer.class) {
            return store.get(BALANCER_KEY, Balancer.class);
        } else if (type == HttpClient.class) {
            return store.get(HTTP_CLIENT_KEY, HttpClient.class);
        }

        return null;
    }

    @Override
    public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
        // Can be used for field injection if needed
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass(), context.getRequiredTestMethod()));
    }

    /**
     * Test cluster context providing access to balancer and workers.
     */
    public static class TestCluster {
        private final ExtensionContext.Store store;

        TestCluster(ExtensionContext.Store store) {
            this.store = store;
        }

        public Balancer getBalancer() {
            return store.get(BALANCER_KEY, Balancer.class);
        }

        public HttpClient getHttpClient() {
            return store.get(HTTP_CLIENT_KEY, HttpClient.class);
        }

        /**
         * Start worker nodes.
         */
        public void startWorkers(int count) {
            startWorkers(count, null);
        }

        /**
         * Start worker nodes with custom JVM options.
         *
         * @param count number of workers to start (1-4)
         * @param javaOpts JVM options (e.g. "-Xms64m -Xmx2g"), or null for default
         */
        public void startWorkers(int count, String javaOpts) {
            startWorkers(count, javaOpts, -1);
        }

        /**
         * Start worker nodes with pre-configured max-attempts.
         *
         * @param count number of workers to start (1-4)
         * @param maxAttempts max-attempts value to pre-configure, or -1 for default
         */
        public void startWorkersWithMaxAttempts(int count, int maxAttempts) {
            startWorkers(count, null, maxAttempts);
        }

        /**
         * Start worker nodes with custom JVM options and pre-configured max-attempts.
         *
         * @param count number of workers to start (1-4)
         * @param javaOpts JVM options, or null for default
         * @param maxAttempts max-attempts value to pre-configure, or -1 for default
         */
        private void startWorkers(int count, String javaOpts, int maxAttempts) {
            Balancer balancer = getBalancer();
            String[] keys = {WORKER1_KEY, WORKER2_KEY, WORKER3_KEY, WORKER4_KEY};

            for (int i = 0; i < count && i < keys.length; i++) {
                WildFlyWorker worker = WildFlyWorker.create(keys[i], balancer);
                if (javaOpts != null) worker.withJavaOpts(javaOpts);
                if (maxAttempts >= 0) worker.withMaxAttempts(maxAttempts);
                worker.start();
                store.put(keys[i], worker);
            }
        }

        public WildFlyWorker getWorker1() {
            return store.get(WORKER1_KEY, WildFlyWorker.class);
        }

        public WildFlyWorker getWorker2() {
            return store.get(WORKER2_KEY, WildFlyWorker.class);
        }

        public WildFlyWorker getWorker3() {
            return store.get(WORKER3_KEY, WildFlyWorker.class);
        }

        public WildFlyWorker getWorker4() {
            return store.get(WORKER4_KEY, WildFlyWorker.class);
        }

        /**
         * Returns the worker with the given name (e.g. "worker1", "worker2").
         *
         * @param name the worker name
         * @return the worker container, never null
         * @throws IllegalArgumentException if the name is not a known worker or the worker was not started
         */
        public WildFlyWorker getWorkerByName(String name) {
            WildFlyWorker worker = store.get(name, WildFlyWorker.class);
            if (worker == null) {
                throw new IllegalArgumentException("Worker '" + name + "' not found — was it started?");
            }
            return worker;
        }
    }
}
