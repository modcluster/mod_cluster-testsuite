package org.jboss.modcluster.test.loadbalancing;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests for load balancing group failover scenarios.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class LoadBalancingGroupFailoverTest {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancingGroupFailoverTest.class);
    private static final double MIN_BALANCE_RATIO = 0.55;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that load is distributed across multiple workers by the balancer.
     * Passes if both workers receive requests and the distribution ratio is at least {@link #MIN_BALANCE_RATIO}.
     */
    @Test
    public void testLoadDistributionAcrossWorkers(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

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
                            .as("Both workers should receive requests")
                            .containsKeys("worker1", "worker2");
                    int w1 = dist.getOrDefault("worker1", 0);
                    int w2 = dist.getOrDefault("worker2", 0);
                    double ratio = (double) Math.min(w1, w2) / Math.max(w1, w2);
                    assertThat(ratio)
                            .as("Load should be relatively balanced (got %s)", dist)
                            .isGreaterThanOrEqualTo(MIN_BALANCE_RATIO);
                });

        log.info("Load distribution: {}", lastDistribution.get());
    }

    /**
     * Verifies that the balancer automatically fails over to remaining workers when one worker stops.
     * Passes if all traffic routes to worker2 within 60 seconds after worker1 is stopped.
     */
    @Test
    public void testFailoverWhenWorkerStops(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and receive traffic
        Map<String, Integer> initialDistribution = httpClient.waitForWorkerRegistration(balancerUrl, 2, TestTimeouts.CLUSTER_FORMATION);

        log.info("Initial distribution: {}", initialDistribution);

        // Stop worker1
        log.info("Stopping worker1...");
        cluster.getWorker1().stop();

        // Wait for balancer to detect failure and route to worker2
        // Note: During transition, some requests may timeout as balancer detects worker1 failure
        await().atMost(TestTimeouts.FAILOVER)
                .pollInterval(ofSeconds(3))
                .untilAsserted(() -> {
                    // Use testLoadDistribution which handles connection failures gracefully
                    Map<String, Integer> dist = httpClient.testLoadDistribution(balancerUrl, 10);
                    assertThat(dist)
                            .as("All requests should go to worker2 after worker1 stops")
                            .containsOnlyKeys("worker2");
                    assertThat(dist.get("worker2"))
                            .as("worker2 should be receiving all successful requests")
                            .isGreaterThan(0);
                });

        // Verify all subsequent requests go to worker2
        Map<String, Integer> afterFailoverDistribution = httpClient.testLoadDistribution(balancerUrl, 20);

        log.info("After failover distribution: {}", afterFailoverDistribution);

        softly.assertThat(afterFailoverDistribution)
                .as("Only worker2 should receive requests after worker1 failure")
                .containsOnlyKeys("worker2");

        softly.assertThat(afterFailoverDistribution.get("worker2"))
                .as("Worker2 should receive all successful requests")
                .isGreaterThan(0);
    }

}
