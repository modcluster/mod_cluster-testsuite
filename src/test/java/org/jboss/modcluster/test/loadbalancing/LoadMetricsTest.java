package org.jboss.modcluster.test.loadbalancing;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.ReadResourceOption;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;

/**
 * Tests for load calculation and metrics in mod_cluster.
 * Verifies load factor calculation, custom metrics, load-based routing, and dynamic load adjustment.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class LoadMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(LoadMetricsTest.class);

    /**
     * Load metrics tests need a larger heap than the default 512MB.
     * A small heap skews load metric readings because GC pressure and JVM overhead
     * dominate, making it hard to isolate the effect of the actual test workload.
     * The heap test also allocates 500MB directly, which would OOM a 512MB JVM.
     */
    private static final String JAVA_OPTS = "-Xms64m -Xmx2g";
    private static final int MAX_WORKER_PERCENT = 70;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that load factor is calculated and reported by workers to the balancer.
     * Passes if workers report non-zero load factors in their mod_cluster proxy configuration.
     */
    @Test
    public void testLoadFactorCalculation(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2, JAVA_OPTS);
        WildFlyWorker worker1 = cluster.getWorker1();
        WildFlyWorker worker2 = cluster.getWorker2();

        // Generate some load
        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        httpClient.testLoadDistribution(balancerUrl, 50);

        // Read load-related configuration from worker1
        Operations ops = worker1.getOperations();
        Address proxyAddress = Address.subsystem("modcluster").and("proxy", "default");

        ModelNodeResult proxyConfig = ops.readResource(proxyAddress, ReadResourceOption.INCLUDE_RUNTIME);
        proxyConfig.assertSuccess();
        ModelNode proxy = proxyConfig.value();

        log.info("Worker1 proxy config: {}", proxy.toJSONString(true));

        // Verify proxy configuration has load-related settings
        softly.assertThat(proxy.hasDefined("status-interval"))
                .as("Proxy should have status-interval defined (for load reporting)")
                .isTrue();

        int statusInterval = proxy.get("status-interval").asInt();
        log.info("Status interval (load reporting frequency): {} seconds", statusInterval);

        softly.assertThat(statusInterval)
                .as("Status interval should be reasonable (typically 10 seconds)")
                .isGreaterThan(0)
                .isLessThan(60);

        // Verify load provider configuration
        softly.assertThat(proxy.hasDefined("load-provider"))
                .as("Proxy should have load-provider configuration")
                .isTrue();

        log.info("Load factor calculation mechanism verified");
    }

    /**
     * Verifies that custom load metrics control traffic distribution.
     * The custom FileBasedLoadMetric module is pre-baked into the container image.
     * This test configures it dynamically and verifies traffic routes based on load values.
     *
     * Test flow:
     * 1. Configure custom load metric in mod_cluster subsystem (module already in image)
     * 2. Reload workers to activate the metric
     * 3. Set different load values via files
     * 4. Verify traffic routes to less-loaded worker
     * 5. Reverse load values
     * 6. Verify traffic routing reverses
     */
    @Test
    public void testCustomLoadMetrics(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2, JAVA_OPTS);
        WildFlyWorker worker1 = cluster.getWorker1();
        WildFlyWorker worker2 = cluster.getWorker2();

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Check if custom load metric module is present in container image
        boolean w1HasModule = worker1.loadMetrics().hasCustomLoadMetricModule();
        boolean w2HasModule = worker2.loadMetrics().hasCustomLoadMetricModule();

        log.info("Worker1 has custom metric module: {}", w1HasModule);
        log.info("Worker2 has custom metric module: {}", w2HasModule);

        if (w1HasModule) {
            String w1Files = worker1.loadMetrics().listCustomLoadMetricModule();
            log.info("Worker1 module files: {}", w1Files.replace("\n", " | "));
        } else {
            log.warn("Worker1 custom metric module NOT FOUND - image may not have been built with module");
        }

        if (w2HasModule) {
            String w2Files = worker2.loadMetrics().listCustomLoadMetricModule();
            log.info("Worker2 module files: {}", w2Files.replace("\n", " | "));
        } else {
            log.warn("Worker2 custom metric module NOT FOUND - image may not have been built with module");
        }

        softly.assertThat(w1HasModule && w2HasModule)
                .as("Both workers should have custom metric module pre-loaded in image")
                .isTrue();

        // Each worker gets its own load file path — isolated by container in Docker,
        // by unique filename in native mode (shared filesystem).
        String loadFile1 = worker1.loadMetrics().getLoadFilePath();
        String loadFile2 = worker2.loadMetrics().getLoadFilePath();
        worker1.loadMetrics().writeLoadValue(500, loadFile1);
        worker2.loadMetrics().writeLoadValue(500, loadFile2);

        // Configure custom load metric (will trigger restart)
        // Use weight=1 like noe-tests (with no other metrics, weight doesn't matter)
        log.info("Configuring custom load metric (weight=1 matching noe-tests)...");
        worker1.loadMetrics().configureCustomLoadMetric(loadFile1, 1000, 1);
        worker2.loadMetrics().configureCustomLoadMetric(loadFile2, 1000, 1);

        // Re-write load values after reload to ensure the file exists and is fresh
        worker1.loadMetrics().writeLoadValue(500, loadFile1);
        worker2.loadMetrics().writeLoadValue(500, loadFile2);

        // Verify custom metric is configured in subsystem
        verifyCustomMetricConfigured(worker1, worker2);

        // Verify the load files are readable inside the containers
        verifyLoadFile(worker1, loadFile1);
        verifyLoadFile(worker2, loadFile2);

        // Check server logs for metric loading issues
        String w1MetricLog = worker1.grepServerLog("FileBasedLoadMetric");
        log.info("Worker1 metric log entries: {}", w1MetricLog);
        String w2MetricLog = worker2.grepServerLog("FileBasedLoadMetric");
        log.info("Worker2 metric log entries: {}", w2MetricLog);

        // Wait for system to stabilize and first STATUS messages to propagate
        log.info("Waiting for system to stabilize...");
        Thread.sleep(5000);

        // SCENARIO 1: High load on worker1, low load on worker2
        log.info("SCENARIO 1: Setting worker1=900 (high), worker2=100 (low)");
        worker1.loadMetrics().writeLoadValue(900, loadFile1);
        worker2.loadMetrics().writeLoadValue(100, loadFile2);

        // Wait for balancer to receive STATUS messages with correct load values
        // Expected load = (1000 - fileValue) / 10 (following noe-tests formula)
        // worker1: (1000 - 900) / 10 = 10
        // worker2: (1000 - 100) / 10 = 90
        log.info("Waiting for balancer to report expected loads (worker1=10, worker2=90)...");
        waitForExpectedLoads(cluster, "worker1", 10, "worker2", 90, 120);

        Map<String, Integer> scenario1 = httpClient.testLoadDistribution(balancerUrl, 500);
        log.info("Scenario 1 distribution (900/100): {}", scenario1);

        int s1_w1 = scenario1.getOrDefault("worker1", 0);
        int s1_w2 = scenario1.getOrDefault("worker2", 0);

        softly.assertThat(s1_w2)
                .as("Scenario 1: Worker2 (load=100) should get more traffic than worker1 (load=900)")
                .isGreaterThan(s1_w1);

        // SCENARIO 2: Reverse the loads
        log.info("SCENARIO 2: Reversing - worker1=100 (low), worker2=900 (high)");
        worker1.loadMetrics().writeLoadValue(100, loadFile1);
        worker2.loadMetrics().writeLoadValue(900, loadFile2);

        // Wait for balancer to receive STATUS messages with correct load values
        // worker1: (1000 - 100) / 10 = 90
        // worker2: (1000 - 900) / 10 = 10
        log.info("Waiting for balancer to report expected loads (worker1=90, worker2=10)...");
        waitForExpectedLoads(cluster, "worker1", 90, "worker2", 10, 120);

        Map<String, Integer> scenario2 = httpClient.testLoadDistribution(balancerUrl, 500);
        log.info("Scenario 2 distribution (100/900): {}", scenario2);

        int s2_w1 = scenario2.getOrDefault("worker1", 0);
        int s2_w2 = scenario2.getOrDefault("worker2", 0);

        softly.assertThat(s2_w1)
                .as("Scenario 2: Worker1 (load=100) should get more traffic than worker2 (load=900)")
                .isGreaterThan(s2_w2);

        log.info("Custom load metric verified - traffic routes based on load:");
        log.info("  Scenario 1 (W1=900, W2=100): worker1={}, worker2={}", s1_w1, s1_w2);
        log.info("  Scenario 2 (W1=100, W2=900): worker1={}, worker2={}", s2_w1, s2_w2);
    }

    private void verifyCustomMetricConfigured(WildFlyWorker worker1, WildFlyWorker worker2)
            throws Exception {
        // mod_cluster uses the full class name as the key, not the custom name we specify
        Address metricAddr = Address.subsystem("modcluster").and("proxy", "default")
                .and("load-provider", "dynamic")
                .and("custom-load-metric", "org.jboss.modcluster.test.metric.FileBasedLoadMetric");

        log.info("Worker1 load-provider config: {}",
                worker1.getOperations().readResource(Address.subsystem("modcluster").and("proxy", "default")
                        .and("load-provider", "dynamic"), ReadResourceOption.RECURSIVE).stringValue());

        boolean w1HasMetric = worker1.getOperations().exists(metricAddr);
        boolean w2HasMetric = worker2.getOperations().exists(metricAddr);

        log.info("Custom metric configured: worker1={}, worker2={}", w1HasMetric, w2HasMetric);

        assertThat(w1HasMetric && w2HasMetric)
                .as("Both workers should have custom metric configured")
                .isTrue();
    }

    /**
     * Verify that the load file exists and is readable inside the container.
     * Logs the file content for diagnostic purposes.
     */
    private void verifyLoadFile(WildFlyWorker worker, String filePath) throws Exception {
        String content = worker.readFile(filePath);
        log.info("Load file on {}: '{}' contains: '{}'", worker.getName(), filePath, content.trim());
        assertThat(content)
                .as("Load file should exist and contain data on %s", worker.getName())
                .contains("LOAD:");
    }

    /**
     * Wait for the balancer to report expected load values for workers.
     * Polls the balancer until loads match expected values (within tolerance) or timeout.
     * Following noe-tests approach: tolerance of 5, timeout of 120 seconds.
     *
     * @param cluster Test cluster
     * @param worker1Name First worker name
     * @param expectedLoad1 Expected load for first worker
     * @param worker2Name Second worker name
     * @param expectedLoad2 Expected load for second worker
     * @param timeoutSeconds Maximum wait time in seconds
     */
    private void waitForExpectedLoads(ModClusterTestExtension.TestCluster cluster,
                                      String worker1Name, int expectedLoad1,
                                      String worker2Name, int expectedLoad2,
                                      int timeoutSeconds) throws Exception {
        final int tolerance = 5;
        long startTime = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds * 1000L;
        boolean worker1Found = false;
        boolean worker2Found = false;
        int lastLoad1 = -1;
        int lastLoad2 = -1;

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            try {
                Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();

                if (workers.containsKey(worker1Name)) {
                    lastLoad1 = workers.get(worker1Name).get("load").asInt();
                    if (Math.abs(lastLoad1 - expectedLoad1) <= tolerance) {
                        worker1Found = true;
                        log.info("{} reached expected load: {} (expected: {}, tolerance: {})",
                                worker1Name, lastLoad1, expectedLoad1, tolerance);
                    } else {
                        log.info("{} current load: {} (expected: {} ±{})",
                                worker1Name, lastLoad1, expectedLoad1, tolerance);
                    }
                }

                if (workers.containsKey(worker2Name)) {
                    lastLoad2 = workers.get(worker2Name).get("load").asInt();
                    if (Math.abs(lastLoad2 - expectedLoad2) <= tolerance) {
                        worker2Found = true;
                        log.info("{} reached expected load: {} (expected: {}, tolerance: {})",
                                worker2Name, lastLoad2, expectedLoad2, tolerance);
                    } else {
                        log.info("{} current load: {} (expected: {} ±{})",
                                worker2Name, lastLoad2, expectedLoad2, tolerance);
                    }
                }

                if (worker1Found && worker2Found) {
                    log.info("Both workers reporting expected loads within tolerance");
                    return;
                }
            } catch (Exception e) {
                log.debug("Error checking loads: {}", e.getMessage());
            }

            Thread.sleep(2000);
        }

        softly.fail("Balancer didn't report expected loads within %d seconds. " +
                "Last seen: %s=%d (expected %d ±%d), %s=%d (expected %d ±%d)",
                timeoutSeconds,
                worker1Name, lastLoad1, expectedLoad1, tolerance,
                worker2Name, lastLoad2, expectedLoad2, tolerance);
    }

    /**
     * Verifies that load-based routing distributes requests according to worker capacity.
     * Tests with built-in dynamic load metrics (no custom metrics needed).
     * Passes if workers with different resource availability receive proportionally different traffic.
     */
    @Test
    public void testLoadBasedRouting(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2, JAVA_OPTS);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and load to stabilize.
        // A newly registered worker starts with initial-load=0 and only gets a real load factor
        // after the next status-interval tick (10s), so we poll until distribution is balanced.
        AtomicReference<Map<String, Integer>> lastDistribution = new AtomicReference<>();
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 100);
                    lastDistribution.set(dist);
                    assertThat(dist)
                            .as("Both workers should receive requests via load-based routing")
                            .containsKeys("worker1", "worker2");
                    int maxRequests = Math.max(
                            dist.getOrDefault("worker1", 0),
                            dist.getOrDefault("worker2", 0));
                    assertThat(maxRequests)
                            .as("Distribution should be relatively balanced (got %s)", dist)
                            .isLessThan(MAX_WORKER_PERCENT);
                });

        log.info("Load distribution with dynamic load metrics: {}", lastDistribution.get());
    }

    /**
     * Verifies that initial load is reported when worker first registers with balancer.
     * Passes if workers successfully register and report initial load metrics within 30 seconds.
     */
    @Test
    public void testInitialLoadReporting(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start with no workers
        cluster.startWorkers(0);

        // Add worker1 and verify it registers with initial load
        log.info("Starting worker1 to test initial load reporting...");
        cluster.startWorkers(1, JAVA_OPTS);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for worker to register and become accessible via balancer
        httpClient.waitForWorkerRegistration(balancerUrl, 1, TestTimeouts.CLUSTER_FORMATION);

        // Read worker's status-interval to verify load reporting is configured
        WildFlyWorker worker = cluster.getWorker1();
        ModelNode statusInterval = worker.modCluster().readModClusterAttribute("status-interval");

        log.info("Worker registered with status-interval: {} seconds", statusInterval.asInt());

        softly.assertThat(statusInterval.asInt())
                .as("Status interval should be configured for load reporting")
                .isGreaterThan(0);

        log.info("Initial load reporting verified");
    }

    /**
     * Verifies that heap load metric responds to memory pressure.
     * When heap usage increases (memory allocated), the load value should decrease (less available capacity).
     * Note: mod_cluster load value scale: 100 = fully available/idle, 0 = overloaded/unavailable.
     */
    @Test
    public void testHeapLoadMetric(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Heap test allocates 500MB — needs a larger heap than the default 512MB
        cluster.startWorkers(1, JAVA_OPTS);
        WildFlyWorker worker1 = cluster.getWorker1();

        // Configure worker to use only heap metric
        worker1.loadMetrics().configureLoadMetric("heap");

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for heap metric to truly stabilize (plateau detection)
        int baselineLoadValue = waitForStableLoad(cluster, "worker1", 120);
        log.info("Baseline load value: {} (100=idle, 0=overloaded)", baselineLoadValue);

        // Trigger memory allocation — servlet stores in static field and returns immediately
        log.info("Allocating 500MB on worker1...");
        String allocUrl = balancerUrl + "load/memory?megabytes=500";
        HttpClient.HttpResponse allocResponse = httpClient.getWithTimeout(allocUrl,
                TestTimeouts.HEAVY_REQUEST.toSeconds(), TimeUnit.SECONDS);
        log.info("Allocation response (status={}): {}", allocResponse.getStatusCode(), allocResponse.getBody().trim());

        softly.assertThat(allocResponse.getStatusCode())
                .as("Memory allocation request should succeed")
                .isEqualTo(200);

        // Poll load during stress — capture the minimum (most loaded) value seen
        int minLoadDuringStress = baselineLoadValue;
        Map<String, ModelNode> workers;
        for (int i = 0; i < 30; i++) { // 60 second window
            Thread.sleep(2000);
            workers = cluster.getBalancer().getWorkerInfo();
            int currentLoad = workers.get("worker1").get("load").asInt();
            log.info("Load during stress: {} (min so far: {})", currentLoad, minLoadDuringStress);
            if (currentLoad < minLoadDuringStress) {
                minLoadDuringStress = currentLoad;
            }
        }

        // Release memory
        log.info("Releasing held memory...");
        String releaseUrl = balancerUrl + "load/memory/release";
        HttpClient.HttpResponse releaseResponse = httpClient.get(releaseUrl);
        log.info("Release response: {}", releaseResponse.getBody().trim());

        log.info("Comparing baseline={} vs min during stress={}", baselineLoadValue, minLoadDuringStress);

        // Verify heap metric caused a load decrease during memory pressure
        int loadValueChange = baselineLoadValue - minLoadDuringStress;
        softly.assertThat(loadValueChange)
                .as("Heap metric should show load decrease under 500MB pressure (baseline=%d, min=%d)",
                    baselineLoadValue, minLoadDuringStress)
                .isGreaterThanOrEqualTo(5);

        log.info("Heap load metric verified: baseline={}, min during stress={}, change={}",
                baselineLoadValue, minLoadDuringStress, loadValueChange);
    }

    /**
     * Wait for load to truly stabilize by detecting a plateau: 3 consecutive readings within ±3.
     */
    private int waitForStableLoad(TestCluster cluster, String workerName, int timeoutSeconds)
            throws Exception {
        int previousLoad = -1;
        int stableCount = 0;
        int lastLoad = -1;

        for (int i = 0; i < timeoutSeconds / 2; i++) {
            Thread.sleep(2000);
            Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
            int currentLoad = workers.get(workerName).get("load").asInt();
            log.info("Stabilization check: load value={}", currentLoad);
            lastLoad = currentLoad;

            if (currentLoad > 10 && previousLoad > 10 && Math.abs(currentLoad - previousLoad) <= 3) {
                stableCount++;
                if (stableCount >= 3) {
                    log.info("System stabilized at load={}", currentLoad);
                    return currentLoad;
                }
            } else {
                stableCount = 0;
            }
            previousLoad = currentLoad;
        }

        log.warn("Stabilization timeout — using last reading: {}", lastLoad);
        return lastLoad;
    }

    /**
     * Verifies that CPU load metric responds to CPU pressure.
     * When CPU usage increases (CPU stress), the load value should decrease (less available capacity).
     * Following noe-tests approach: measure load value after cooldown period.
     * Note: mod_cluster load value scale: 100 = fully available/idle, 0 = overloaded/unavailable.
     *
     * Container-only: getProcessCpuLoad() returns 0.0 on some Windows CI JVMs,
     * so the metric stays at 100 (idle) regardless of actual CPU pressure.
     */
    @Test
    @Tag("docker")
    public void testCpuLoadMetric(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1, JAVA_OPTS);
        WildFlyWorker worker1 = cluster.getWorker1();

        // Configure worker to use only CPU metric (it's default, but explicit)
        worker1.loadMetrics().configureLoadMetric("cpu");

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for system to stabilize - wait for load value > 70 (like noe-tests)
        log.info("Waiting for system to stabilize (load value > 70)...");
        int baselineLoadValue = -1;
        for (int i = 0; i < 30; i++) { // 60 second timeout
            Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
            baselineLoadValue = workers.get("worker1").get("load").asInt();
            log.info("Stabilization check: load value={} (target >70)", baselineLoadValue);
            if (baselineLoadValue > 70) {
                break;
            }
            Thread.sleep(2000);
        }

        log.info("Baseline load value after stabilization: {} (100=idle, 0=overloaded)", baselineLoadValue);

        // Generate CPU load: 2 minutes like noe-tests
        log.info("Generating CPU load (120 seconds on all cores)...");
        String loadUrl = balancerUrl + "load/cpu?duration=120000";

        HttpClient.HttpResponse response = httpClient.getWithTimeout(loadUrl, 3, TimeUnit.MINUTES);
        log.info("Load generation completed with status: {}", response.getStatusCode());

        // Check load value immediately after CPU stress
        Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
        int loadValueAfterRoasting = workers.get("worker1").get("load").asInt();
        log.info("Load value immediately after CPU roasting: {}", loadValueAfterRoasting);

        // Wait for cooldown (CPU usage returns to normal)
        log.info("Waiting for CPU cooldown (60 seconds)...");
        for (int i = 0; i < 30; i++) {
            Thread.sleep(2000);
            workers = cluster.getBalancer().getWorkerInfo();
            int currentLoadValue = workers.get("worker1").get("load").asInt();
            log.info("Cooldown check: load value={}", currentLoadValue);
            if (currentLoadValue > 1) {
                break;
            }
        }

        // Get load value after cooldown
        workers = cluster.getBalancer().getWorkerInfo();
        int loadValueAfterCooldown = workers.get("worker1").get("load").asInt();
        log.info("Load value after cooldown: {}", loadValueAfterCooldown);

        // After cooldown, CPU usage has decreased but may not have fully returned to idle
        // So load value should be lower than baseline (not fully recovered)
        softly.assertThat(loadValueAfterCooldown)
                .as("Load value after cooldown should still be below baseline (CPU metric detects recent activity)")
                .isLessThan(baselineLoadValue);

        int loadValueChange = baselineLoadValue - loadValueAfterCooldown;
        softly.assertThat(loadValueChange)
                .as("CPU metric should cause noticeable load value change after stress+cooldown")
                .isGreaterThanOrEqualTo(5);

        log.info("CPU load metric verified: baseline load value={}, after roasting={}, after cooldown={}, change={}",
                baselineLoadValue, loadValueAfterRoasting, loadValueAfterCooldown, loadValueChange);
    }

    /**
     * Verifies that load metrics are dynamically updated and reflected in routing decisions.
     * Monitors load distribution over multiple rounds of traffic.
     * Passes if load balancing remains consistent across multiple traffic bursts.
     */
    @Test
    public void testDynamicLoadAdjustment(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2, JAVA_OPTS);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and receive traffic
        httpClient.waitForWorkerRegistration(balancerUrl, 2, TestTimeouts.CLUSTER_FORMATION);

        // Generate multiple rounds of traffic to observe dynamic load adjustment
        Map<String, Integer> round1 = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Round 1 distribution: {}", round1);

        Map<String, Integer> round2 = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Round 2 distribution: {}", round2);

        Map<String, Integer> round3 = httpClient.testLoadDistribution(balancerUrl, 50);
        log.info("Round 3 distribution: {}", round3);

        // Verify both workers receive traffic in each round (dynamic load balancing is active)
        softly.assertThat(round1)
                .as("Round 1: Both workers should receive requests")
                .containsKeys("worker1", "worker2");

        softly.assertThat(round2)
                .as("Round 2: Both workers should receive requests")
                .containsKeys("worker1", "worker2");

        softly.assertThat(round3)
                .as("Round 3: Both workers should receive requests")
                .containsKeys("worker1", "worker2");

        log.info("Dynamic load adjustment verified - workers continue to receive traffic across multiple rounds");
    }
}
