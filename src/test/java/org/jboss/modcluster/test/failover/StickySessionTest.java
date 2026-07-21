package org.jboss.modcluster.test.failover;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Duration.ofSeconds;
import static java.time.Duration.ofMillis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;

/**
 * Tests for sticky session functionality with mod_cluster.
 * Verifies that requests with session cookies are routed to the same worker.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class StickySessionTest {

    private static final Logger log = LoggerFactory.getLogger(StickySessionTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that requests with the same session cookie are consistently routed to the same worker.
     * Passes if 10 consecutive requests with the same JSESSIONID all route to the initially assigned worker.
     */
    @Test
    public void testStickySessionsMaintainedAcrossRequests(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start two workers
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Make initial request to establish session
        HttpResponse initialResponse = httpClient.get(balancerUrl);
        softly.assertThat(initialResponse.getStatusCode())
                .as("Initial request should return 200 OK")
                .isEqualTo(200);

        String sessionCookie = initialResponse.getCookie("JSESSIONID");
        softly.assertThat(sessionCookie)
                .as("Session cookie should be set")
                .isNotNull();

        log.info("Session established: {}", sessionCookie);

        // Extract route/worker from session cookie
        String initialWorker = extractWorkerFromSessionId(sessionCookie);
        log.info("Initial worker: {}", initialWorker);

        // Make 10 subsequent requests with the same session cookie
        for (int i = 0; i < 10; i++) {
            HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);

            softly.assertThat(response.getStatusCode())
                    .as("Request %d should return 200 OK", i + 1)
                    .isEqualTo(200);

            String currentWorker = extractWorkerFromResponse(response);
            softly.assertThat(currentWorker)
                    .as("Request %d should route to same worker", i + 1)
                    .isEqualTo(initialWorker);

            log.debug("Request {} -> Worker: {}", i + 1, currentWorker);
        }
    }

    /**
     * Verifies that multiple concurrent clients each maintain sticky session affinity to their assigned worker.
     * Passes if 5 simulated clients each make 5 requests that all route to their respective assigned workers.
     */
    @Test
    public void testSessionAffinityWithMultipleClients(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register and report real load factors.
        // With initial-load=0, a newly registered worker may not get a JVM route
        // appended to JSESSIONID until the balancer receives a STATUS message.
        httpClient.waitForWorkerRegistration(balancerUrl, 2, TestTimeouts.CLUSTER_FORMATION);

        // Simulate 5 different clients with different sessions
        for (int client = 1; client <= 5; client++) {
            HttpResponse initialResponse = httpClient.get(balancerUrl);
            String sessionCookie = initialResponse.getCookie("JSESSIONID");
            String assignedWorker = extractWorkerFromSessionId(sessionCookie);

            log.info("Client {} assigned to worker: {}", client, assignedWorker);

            // Each client makes 5 requests with their session
            for (int req = 1; req <= 5; req++) {
                HttpResponse response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
                String currentWorker = extractWorkerFromResponse(response);

                softly.assertThat(currentWorker)
                        .as("Client %d request %d should route to worker %s", client, req, assignedWorker)
                        .isEqualTo(assignedWorker);
            }
        }
    }

    /**
     * Verifies that sticky-session-force=true causes 503 when the sticky worker is killed.
     * With sticky-session-force enabled, the balancer refuses to failover to another worker
     * and returns 503 Service Unavailable instead.
     */
    @Test
    public void testStickySessionForce(TestCluster cluster, HttpClient httpClient) throws Exception {
        doStickySessionFailoverTest(cluster, httpClient, true, false, 503);
    }

    /**
     * Verifies that sticky-session-force=false allows failover when the sticky worker is killed.
     * The session is preserved via distributed session cache and the request succeeds on a different worker.
     */
    @Test
    public void testStickySessionFailover(TestCluster cluster, HttpClient httpClient) throws Exception {
        doStickySessionFailoverTest(cluster, httpClient, false, false, 200);
    }

    /**
     * Verifies that sticky-session-force=true causes 503 when the sticky worker is killed
     * and the session ID is passed via URL encoding instead of a cookie header.
     * URL format: /demo/;jsessionid=sessionid
     */
    @Test
    public void testStickySessionForceWithUrlEncodedSession(TestCluster cluster, HttpClient httpClient) throws Exception {
        doStickySessionFailoverTest(cluster, httpClient, true, true, 503);
    }

    /**
     * Verifies that sticky-session-force=false allows failover when the sticky worker is killed
     * and the session ID is passed via URL encoding instead of a cookie header.
     * URL format: /demo/;jsessionid=sessionid
     */
    @Test
    public void testStickySessionFailoverWithUrlEncodedSession(TestCluster cluster, HttpClient httpClient) throws Exception {
        doStickySessionFailoverTest(cluster, httpClient, false, true, 200);
    }

    /**
     * Common implementation for sticky session failover tests.
     * Configures sticky session settings on both workers, establishes a session,
     * kills the worker handling the session, and verifies the expected HTTP response code.
     *
     * @param cluster the test cluster
     * @param httpClient the HTTP client
     * @param stickySessionForce whether to enable sticky-session-force (true returns 503 on failover)
     * @param useUrlEncodedSession whether to pass JSESSIONID in URL instead of cookie header
     * @param expectedStatusCode the expected HTTP status code after killing the sticky worker
     */
    private void doStickySessionFailoverTest(TestCluster cluster, HttpClient httpClient,
                                              boolean stickySessionForce, boolean useUrlEncodedSession,
                                              int expectedStatusCode) throws Exception {
        cluster.startWorkers(2);
        final WildFlyWorker worker1 = cluster.getWorker1();
        final WildFlyWorker worker2 = cluster.getWorker2();

        // Configure sticky session settings on both workers (batch config before reloads)
        worker1.modCluster().setStickySession(true);
        worker1.modCluster().setStickySessionForce(stickySessionForce);
        worker1.modCluster().setStickySessionRemove(false);

        worker2.modCluster().setStickySession(true);
        worker2.modCluster().setStickySessionForce(stickySessionForce);
        worker2.modCluster().setStickySessionRemove(false);

        // Reload sequentially after all config is set
        worker1.reload();
        worker2.reload();

        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for both workers to register with the balancer after reload
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse resp = httpClient.get(balancerUrl);
                    assertThat(resp.getStatusCode()).isEqualTo(200);
                });

        // Establish a session via initial request
        final HttpResponse initialResponse = httpClient.get(balancerUrl);
        final String sessionCookie = initialResponse.getCookie("JSESSIONID");
        softly.assertThat(sessionCookie)
                .as("Session cookie should be set on initial request")
                .isNotNull();

        final String initialWorker = extractWorkerFromSessionId(sessionCookie);
        log.info("Session established: {} on worker: {}", sessionCookie, initialWorker);

        // Kill the worker handling the session
        if ("worker1".equals(initialWorker)) {
            log.info("Killing worker1 (session holder)...");
            worker1.kill();
        } else {
            log.info("Killing worker2 (session holder)...");
            worker2.kill();
        }

        // Send request with existing session and verify expected status code.
        // For force=false (expecting 200), use Awaitility to retry — httpd's mod_proxy_cluster
        // detects dead workers on-demand (TCP connect failure), so the first request after kill
        // may timeout or return 503 while httpd marks the dead worker and fails over.
        // For force=true (expecting 503), a single request suffices since httpd returns 503 immediately.
        if (expectedStatusCode == 200) {
            await().atMost(TestTimeouts.FAILOVER).pollInterval(ofSeconds(2))
                    .ignoreExceptionsInstanceOf(IOException.class)
                    .untilAsserted(() -> {
                        final HttpResponse response;
                        if (useUrlEncodedSession) {
                            final String urlWithSession = cluster.getBalancer().getHttpUrl()
                                    + "/" + DEMO_APP + "/;jsessionid=" + sessionCookie;
                            response = httpClient.get(urlWithSession);
                        } else {
                            response = httpClient.getWithSession(balancerUrl, "JSESSIONID=" + sessionCookie);
                        }
                        assertThat(response.getStatusCode())
                                .as("Expected HTTP 200 after killing sticky worker (force=%s, urlEncoded=%s)",
                                        stickySessionForce, useUrlEncodedSession)
                                .isEqualTo(200);
                    });
        } else {
            // force=true: get the first non-IOException response after the kill.
            // The 503 is transient — the balancer eventually removes the dead node
            // and starts routing to survivors (200). Capture the first response only.
            final AtomicReference<HttpResponse> responseRef = new AtomicReference<>();
            await().atMost(TestTimeouts.FAILOVER).pollInterval(ofMillis(500))
                    .until(() -> {
                        try {
                            if (useUrlEncodedSession) {
                                final String urlWithSession = cluster.getBalancer().getHttpUrl()
                                        + "/" + DEMO_APP + "/;jsessionid=" + sessionCookie;
                                responseRef.set(httpClient.get(urlWithSession));
                            } else {
                                responseRef.set(httpClient.getWithSession(balancerUrl,
                                        "JSESSIONID=" + sessionCookie));
                            }
                            return true;
                        } catch (IOException e) {
                            return false;
                        }
                    });

            softly.assertThat(responseRef.get().getStatusCode())
                    .as("Expected HTTP 503 after killing sticky worker (force=%s, urlEncoded=%s)",
                            stickySessionForce, useUrlEncodedSession)
                    .isEqualTo(503);
        }

        log.info("Sticky session test completed: force={}, urlEncoded={}, expectedStatus={}",
                stickySessionForce, useUrlEncodedSession, expectedStatusCode);
    }

    /**
     * Extract worker/route information from JSESSIONID.
     * Format is typically: <session-id>.<route>
     */
    private String extractWorkerFromSessionId(String sessionId) {
        if (sessionId != null && sessionId.contains(".")) {
            return sessionId.substring(sessionId.lastIndexOf('.') + 1);
        }
        return "unknown";
    }

    /**
     * Extract worker from response (assumes app returns worker identity).
     */
    private String extractWorkerFromResponse(HttpResponse response) {
        String body = response.getBody();
        if (body.contains("worker1")) return "worker1";
        if (body.contains("worker2")) return "worker2";

        // Fallback to session ID route
        String sessionCookie = response.getCookie("JSESSIONID");
        if (sessionCookie != null) {
            return extractWorkerFromSessionId(sessionCookie);
        }

        return "unknown";
    }
}
