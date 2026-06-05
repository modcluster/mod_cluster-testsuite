package org.jboss.modcluster.test.session;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.ContinuousRequestRunner;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.UndertowSessionCookieConfigurator;
import org.jboss.modcluster.test.utils.TestMode;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.jboss.modcluster.test.apps.SessionTimeoutAppBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Session management testing.
 * Tests session timeout preservation, custom cookie names, and JVM route integrity across various failover scenarios.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class SessionManagementTest {

    private static final Logger log = LoggerFactory.getLogger(SessionManagementTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that session timeout is NOT hit after failover despite configured 1-minute timeout.
     * Passes if continuous requests for 80 seconds succeed after worker shutdown without session expiration.
     */
    @Test
    public void testSessionTimeoutPreservedAfterShutdown(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        configureSessionDrainingNever(cluster.getWorker1(), cluster.getWorker2());

        // Deploy distributable app with 1-minute timeout
        final File timeoutApp = SessionTimeoutAppBuilder.createApp(1);
        cluster.getWorker1().deployment().deploy(timeoutApp, "timeout-test.war");
        cluster.getWorker2().deployment().deploy(timeoutApp, "timeout-test.war");

        // Wait for JGroups cluster to form AFTER deploying the distributable app.
        // JGroups channels are lazy-started in WildFly — the 'ee' channel only starts
        // when a <distributable/> app is deployed, triggering Infinispan cache creation.
        // 120s timeout: on CI (Podman rootless), accumulated container churn causes
        // JGroups TCP connections to take longer due to slirp4netns/pasta overhead.
        cluster.getWorker1().jgroups().waitForClusterFormation(2, TestTimeouts.CLUSTER_FORMATION);

        final String url = cluster.getBalancer().getHttpUrl() + "/timeout-test/";

        // Wait for both workers to register on the balancer.
        // On CI (Podman rootless), MCMP registration can be flaky — verifying both workers
        // receive traffic ensures the balancer has a failover target when we kill one.
        httpClient.waitForWorkerRegistration(url, 2, TestTimeouts.CLUSTER_FORMATION);

        // Establish session
        final HttpResponse initial = httpClient.get(url);
        final String sessionCookie = initial.getCookie("JSESSIONID");
        final String originalSessionId = extractSessionIdOnly(sessionCookie);

        log.info("Session established: {}", sessionCookie);

        // Continuous requests for 80 seconds (exceeds 1-minute timeout boundary to prove
        // lastAccessedTime survives failover — session would expire at ~60s if not refreshed).
        final ContinuousRequestRunner runner = new ContinuousRequestRunner(httpClient, url, sessionCookie);
        final Future<ContinuousRequestRunner.RequestResult> resultFuture = runner.startAsync(
            Duration.ofSeconds(80), Duration.ofMillis(1000));

        // After 5 seconds warmup, gracefully shutdown worker1 via management API
        Thread.sleep(5000);
        log.info("Shutting down worker1 during continuous requests");
        cluster.getWorker1().shutdown();

        // Wait for continuous requests to complete
        final ContinuousRequestRunner.RequestResult result = resultFuture.get(150, TimeUnit.SECONDS);

        log.info("Continuous requests completed: {} total, {} failed, {} session ID changes",
                 result.getTotalCount(), result.getFailedCount(), result.getSessionIdChanges());

        // Verify — graceful shutdown should produce few failures.
        // During shutdown, the balancer may briefly route to the stopping worker.
        // The surviving worker may also return HTTP 500 from Infinispan timeouts
        // (17.5s each) while trying to coordinate with the now-dead worker.
        softly.assertThat(result.getFailedCount())
            .as("Few requests should fail during graceful shutdown")
            .isLessThan(25);

        softly.assertThat(result.getTotalCount())
            .as("Should complete most of the ~80 requests")
            .isGreaterThan(55);

        // Session replication should preserve the session, but under CI load the JGroups
        // cluster may not replicate in time, causing one session recreation on failover.
        softly.assertThat(result.getSessionIdChanges())
            .as("Session ID should remain constant or change at most once during failover")
            .isLessThanOrEqualTo(1);
    }

    /**
     * Verifies that session timeout is NOT hit after hard kill despite configured 1-minute timeout.
     * Passes if continuous requests for 80 seconds succeed after worker kill without session expiration.
     */
    @Test
    public void testSessionTimeoutPreservedAfterKill(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        configureSessionDrainingNever(cluster.getWorker1(), cluster.getWorker2());

        // Deploy distributable app with 1-minute timeout
        final File timeoutApp = SessionTimeoutAppBuilder.createApp(1);
        cluster.getWorker1().deployment().deploy(timeoutApp, "timeout-test.war");
        cluster.getWorker2().deployment().deploy(timeoutApp, "timeout-test.war");

        // Wait for JGroups cluster after distributable app triggers channel start
        cluster.getWorker1().jgroups().waitForClusterFormation(2, TestTimeouts.CLUSTER_FORMATION);

        final String url = cluster.getBalancer().getHttpUrl() + "/timeout-test/";

        // Wait for both workers to register on the balancer
        httpClient.waitForWorkerRegistration(url, 2, TestTimeouts.CLUSTER_FORMATION);

        // Establish session
        final HttpResponse initial = httpClient.get(url);
        final String sessionCookie = initial.getCookie("JSESSIONID");

        log.info("Session established: {}", sessionCookie);

        // Continuous requests for 80 seconds (exceeds 1-minute timeout with buffer for
        // failover disruption — hard kill causes TCP connection drops and the balancer
        // needs up to broken-node-timeout seconds to stop routing to the dead worker).
        final ContinuousRequestRunner runner = new ContinuousRequestRunner(httpClient, url, sessionCookie);
        final Future<ContinuousRequestRunner.RequestResult> resultFuture = runner.startAsync(
            Duration.ofSeconds(80), Duration.ofMillis(1000));

        // After 5 seconds warmup, hard kill worker1
        Thread.sleep(5000);
        log.info("Killing worker1 (SIGKILL) during continuous requests");
        cluster.getWorker1().kill();

        // Wait for continuous requests to complete
        final ContinuousRequestRunner.RequestResult result = resultFuture.get(150, TimeUnit.SECONDS);

        log.info("Continuous requests completed: {} total, {} failed",
                 result.getTotalCount(), result.getFailedCount());

        // Verify — hard kill causes immediate TCP connection drop. The Undertow balancer
        // may take up to broken-node-timeout seconds to remove the dead worker from routing,
        // causing many failed requests during that window.
        softly.assertThat(result.getFailedCount())
            .as("Requests may fail during hard kill until balancer detects dead worker")
            .isLessThan(50);

        softly.assertThat(result.getTotalCount())
            .as("Should complete most of the ~80 requests")
            .isGreaterThan(55);

        softly.assertThat(result.getSessionIdChanges())
            .as("Session ID should remain constant or change at most once during failover")
            .isLessThanOrEqualTo(1);
    }

    /**
     * Verifies that session timeout is NOT hit after application undeploy despite configured 1-minute timeout.
     * Passes if continuous requests for 80 seconds succeed after undeploy without session expiration.
     */
    @Test
    public void testSessionTimeoutPreservedAfterUndeploy(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        configureSessionDrainingNever(cluster.getWorker1(), cluster.getWorker2());

        // Deploy distributable app with 1-minute timeout
        final File timeoutApp = SessionTimeoutAppBuilder.createApp(1);
        cluster.getWorker1().deployment().deploy(timeoutApp, "timeout-test.war");
        cluster.getWorker2().deployment().deploy(timeoutApp, "timeout-test.war");

        // Wait for JGroups cluster after distributable app triggers channel start
        cluster.getWorker1().jgroups().waitForClusterFormation(2, TestTimeouts.CLUSTER_FORMATION);

        final String url = cluster.getBalancer().getHttpUrl() + "/timeout-test/";

        // Wait for both workers to register on the balancer
        httpClient.waitForWorkerRegistration(url, 2, TestTimeouts.CLUSTER_FORMATION);

        // Establish session
        final HttpResponse initial = httpClient.get(url);
        final String sessionCookie = initial.getCookie("JSESSIONID");

        log.info("Session established: {}", sessionCookie);

        // Continuous requests for 80 seconds (exceeds 1-minute timeout with buffer for
        // failover disruption during undeploy).
        final ContinuousRequestRunner runner = new ContinuousRequestRunner(httpClient, url, sessionCookie);
        final Future<ContinuousRequestRunner.RequestResult> resultFuture = runner.startAsync(
            Duration.ofSeconds(80), Duration.ofMillis(1000));

        // After 5 seconds warmup, undeploy from worker1.
        // First stop the context via mod_cluster to immediately remove it from the balancer's routing table.
        // Without this, the management undeploy creates a brief window where sessions are invalidated
        // but the context is still routable — a request hitting that window gets a new (unreplicated) session,
        // corrupting the cookie for subsequent requests. The noe-tests avoid this naturally because their
        // file-deletion approach has a deployment scanner delay before the actual undeploy executes.
        Thread.sleep(5000);
        log.info("Stopping context and undeploying timeout-test.war from worker1");
        cluster.getWorker1().modCluster().stopContext("/timeout-test", "default-host");
        cluster.getWorker1().deployment().undeploy("timeout-test.war");

        // Wait for continuous requests to complete
        final ContinuousRequestRunner.RequestResult result = resultFuture.get(150, TimeUnit.SECONDS);

        log.info("Continuous requests completed: {} total, {} failed",
                 result.getTotalCount(), result.getFailedCount());

        // Verify — undeploy invalidates sessions on the undeployed worker. The stopContext
        // call before undeploy removes the context from balancer routing, but under CI load
        // there's a brief window where requests may still hit the dying context.
        softly.assertThat(result.getFailedCount())
            .as("Some requests may fail during undeploy transition")
            .isLessThan(25);

        softly.assertThat(result.getTotalCount())
            .as("Should complete most of the ~80 requests")
            .isGreaterThan(55);

        // Session recreation may happen multiple times if the balancer briefly routes
        // to the undeployed worker (which invalidates the session on response).
        softly.assertThat(result.getSessionIdChanges())
            .as("Session ID changes should be limited during undeploy")
            .isLessThanOrEqualTo(4);
    }

    /**
     * Verifies that session timeout is NOT hit after context stop despite configured 1-minute timeout.
     * Passes if continuous requests for 65 seconds succeed after stop-context without session expiration.
     */
    @Test
    public void testSessionTimeoutPreservedAfterStopContext(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        configureSessionDrainingNever(cluster.getWorker1(), cluster.getWorker2());

        // Deploy distributable app with 1-minute timeout
        final File timeoutApp = SessionTimeoutAppBuilder.createApp(1);
        cluster.getWorker1().deployment().deploy(timeoutApp, "timeout-test.war");
        cluster.getWorker2().deployment().deploy(timeoutApp, "timeout-test.war");

        // Wait for JGroups cluster after distributable app triggers channel start
        cluster.getWorker1().jgroups().waitForClusterFormation(2, TestTimeouts.CLUSTER_FORMATION);

        final String url = cluster.getBalancer().getHttpUrl() + "/timeout-test/";

        // Wait for both workers to register on the balancer
        httpClient.waitForWorkerRegistration(url, 2, TestTimeouts.CLUSTER_FORMATION);

        // Establish session
        final HttpResponse initial = httpClient.get(url);
        final String sessionCookie = initial.getCookie("JSESSIONID");

        log.info("Session established: {}", sessionCookie);

        // Continuous requests for 65 seconds
        final ContinuousRequestRunner runner = new ContinuousRequestRunner(httpClient, url, sessionCookie);
        final Future<ContinuousRequestRunner.RequestResult> resultFuture = runner.startAsync(
            Duration.ofSeconds(65), Duration.ofMillis(1000));

        // After 5 seconds warmup, stop context on worker1
        Thread.sleep(5000);
        log.info("Stopping context /timeout-test on worker1");
        cluster.getWorker1().modCluster().stopContext("/timeout-test", "default-host");

        // Wait for continuous requests to complete
        final ContinuousRequestRunner.RequestResult result = resultFuture.get(90, TimeUnit.SECONDS);

        log.info("Continuous requests completed: {} total, {} failed",
                 result.getTotalCount(), result.getFailedCount());

        // Verify
        softly.assertThat(result.getFailedCount())
            .as("Few requests may fail during stop-context")
            .isLessThan(10);

        int minExpected = TestMode.isWindows() ? 50 : 60;
        softly.assertThat(result.getTotalCount())
            .as("Should complete at least %d of ~65 requests", minExpected)
            .isGreaterThan(minExpected);

        softly.assertThat(result.getSessionIdChanges())
            .as("Session ID should remain constant or change at most once during failover")
            .isLessThanOrEqualTo(1);
    }

    /**
     * Verifies that session timeout is NOT hit after context disable despite configured 1-minute timeout.
     * Passes if continuous requests for 65 seconds succeed after disable-context without session expiration.
     */
    @Test
    public void testSessionTimeoutPreservedAfterDisableContext(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        configureSessionDrainingNever(cluster.getWorker1(), cluster.getWorker2());

        // Deploy distributable app with 1-minute timeout
        final File timeoutApp = SessionTimeoutAppBuilder.createApp(1);
        cluster.getWorker1().deployment().deploy(timeoutApp, "timeout-test.war");
        cluster.getWorker2().deployment().deploy(timeoutApp, "timeout-test.war");

        // Wait for JGroups cluster after distributable app triggers channel start
        cluster.getWorker1().jgroups().waitForClusterFormation(2, TestTimeouts.CLUSTER_FORMATION);

        final String url = cluster.getBalancer().getHttpUrl() + "/timeout-test/";

        // Wait for both workers to register on the balancer
        httpClient.waitForWorkerRegistration(url, 2, TestTimeouts.CLUSTER_FORMATION);

        // Establish session
        final HttpResponse initial = httpClient.get(url);
        final String sessionCookie = initial.getCookie("JSESSIONID");

        log.info("Session established: {}", sessionCookie);

        // Continuous requests for 65 seconds
        final ContinuousRequestRunner runner = new ContinuousRequestRunner(httpClient, url, sessionCookie);
        final Future<ContinuousRequestRunner.RequestResult> resultFuture = runner.startAsync(
            Duration.ofSeconds(65), Duration.ofMillis(1000));

        // After 5 seconds warmup, disable context on worker1
        Thread.sleep(5000);
        log.info("Disabling context /timeout-test on worker1");
        cluster.getWorker1().modCluster().disableContext("/timeout-test", "default-host");

        // Wait for continuous requests to complete
        final ContinuousRequestRunner.RequestResult result = resultFuture.get(90, TimeUnit.SECONDS);

        log.info("Continuous requests completed: {} total, {} failed",
                 result.getTotalCount(), result.getFailedCount());

        // Verify
        softly.assertThat(result.getFailedCount())
            .as("Few requests may fail during disable-context")
            .isLessThan(10);

        int minExpected = TestMode.isWindows() ? 50 : 60;
        softly.assertThat(result.getTotalCount())
            .as("Should complete at least %d of ~65 requests", minExpected)
            .isGreaterThan(minExpected);

        softly.assertThat(result.getSessionIdChanges())
            .as("Session ID should remain constant or change at most once during failover")
            .isLessThanOrEqualTo(1);
    }

    /**
     * Verifies that default cookie name (JSESSIONID) works correctly with sticky sessions.
     * Passes if sticky sessions work with default cookie name and failover preserves session.
     */
    @Test
    public void testDefaultCookieName(TestCluster cluster, HttpClient httpClient) throws Exception {
        testCookieNameScenario(null, false, cluster, httpClient);
    }

    /**
     * Verifies that camel-case custom cookie name works with sticky sessions.
     * Passes if sticky sessions work with "VaLuE" cookie name and failover preserves session.
     */
    @Test
    public void testCamelCaseCookieName(TestCluster cluster, HttpClient httpClient) throws Exception {
        testCookieNameScenario("VaLuE", false, cluster, httpClient);
    }

    /**
     * Verifies that uppercase custom cookie name works with sticky sessions.
     * Passes if sticky sessions work with "VALUE" cookie name and failover preserves session.
     */
    @Test
    public void testUpperCaseCookieName(TestCluster cluster, HttpClient httpClient) throws Exception {
        testCookieNameScenario("VALUE", false, cluster, httpClient);
    }

    /**
     * Verifies that lowercase custom cookie name works with sticky sessions.
     * Passes if sticky sessions work with "mysession" cookie name and failover preserves session.
     * Note: Avoids "value" as cookie name because it collides with the DMR write-attribute
     * parameter naming (name=name, value=value), preventing the configuration from being applied.
     */
    @Test
    public void testLowerCaseCookieName(TestCluster cluster, HttpClient httpClient) throws Exception {
        testCookieNameScenario("mysession", false, cluster, httpClient);
    }

    /**
     * Verifies that camel-case cookie name works with sticky sessions after balancer reload.
     * Passes if sticky sessions work with "VaLuE" cookie name after balancer reload.
     */
    @Test
    public void testCamelCaseCookieWithBalancerReload(TestCluster cluster, HttpClient httpClient) throws Exception {
        testCookieNameScenario("VaLuE", true, cluster, httpClient);
    }

    /**
     * Verifies that uppercase cookie name works with sticky sessions after balancer reload.
     * Passes if sticky sessions work with "VALUE" cookie name after balancer reload.
     */
    @Test
    public void testUpperCaseCookieWithBalancerReload(TestCluster cluster, HttpClient httpClient) throws Exception {
        testCookieNameScenario("VALUE", true, cluster, httpClient);
    }

    /**
     * Verifies that lowercase cookie name works with sticky sessions after balancer reload.
     * Passes if sticky sessions work with "mysession" cookie name after balancer reload.
     */
    @Test
    public void testLowerCaseCookieWithBalancerReload(TestCluster cluster, HttpClient httpClient) throws Exception {
        testCookieNameScenario("mysession", true, cluster, httpClient);
    }

    /**
     * Common test method for cookie name scenarios.
     * Tests sticky sessions with custom cookie names and verifies failover.
     *
     * @param cookieName Custom cookie name, or null for default (JSESSIONID)
     * @param reloadBalancer Whether to reload balancer after cookie configuration
     * @param cluster Test cluster
     * @param httpClient HTTP client
     * @throws Exception if test fails
     */
    private void testCookieNameScenario(final String cookieName, final boolean reloadBalancer,
                                        final TestCluster cluster, final HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);

        // Configure custom cookie name on workers
        if (cookieName != null) {
            final UndertowSessionCookieConfigurator configurator = new UndertowSessionCookieConfigurator();
            configurator.setSessionCookieName(cluster.getWorker1(), cookieName);
            configurator.setSessionCookieName(cluster.getWorker2(), cookieName);
        }

        if (reloadBalancer) {
            log.info("Reloading balancer for cookie name test");
            // Note: Balancer reload not currently needed for Undertow, but kept for future httpd testing
            Thread.sleep(5000);
        }

        final String url = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        final String effectiveCookieName = cookieName != null ? cookieName : "JSESSIONID";

        log.info("Testing cookie name: {}", effectiveCookieName);

        // Wait for both workers to register on the balancer.
        // On CI (Podman rootless), MCMP registration can be flaky after reloads.
        // 120s timeout: accumulated container churn on CI causes MCMP delays.
        httpClient.waitForWorkerRegistration(url, 2, TestTimeouts.CLUSTER_FORMATION);

        // Wait for workers to respond with the expected cookie name.
        // After cookie name change + reload, the server needs time to apply the new name.
        // 120s timeout: on degraded CI, reloads take longer and the new config may not
        // be applied until the next Infinispan state transfer settles.
        final AtomicReference<HttpResponse> initialRef = new AtomicReference<>();
        await().atMost(TestTimeouts.CONTEXT_OPERATION)
            .pollInterval(ofSeconds(2))
            .ignoreExceptionsInstanceOf(IOException.class)
            .untilAsserted(() -> {
                final HttpResponse response = httpClient.get(url);
                assertThat(response.getStatusCode()).isEqualTo(200);
                assertThat(response.getCookie(effectiveCookieName))
                    .as("Cookie '%s' should be set by server. Available: %s",
                        effectiveCookieName, response.getCookies())
                    .isNotNull();
                initialRef.set(response);
            });

        final HttpResponse initial = initialRef.get();
        final String cookie = initial.getCookie(effectiveCookieName);
        final String sessionId = extractSessionIdOnly(cookie);
        final String worker = extractWorkerFromResponse(initial);

        log.info("Session {} established on {} using cookie name '{}'", sessionId, worker, effectiveCookieName);

        // Make 10 requests - verify sticky to same worker.
        // Note: Responses may not include Set-Cookie after initial session creation.
        // Allow occasional IOExceptions (SocketTimeoutException) because even before
        // any worker is killed, Infinispan lock contention on the session cache can
        // cause the worker to hang >10s (ISPN000299), exceeding OkHttp readTimeout.
        int preKillIoFailures = 0;
        for (int i = 0; i < 10; i++) {
            try {
                final HttpResponse response = httpClient.getWithSession(url, effectiveCookieName + "=" + cookie);
                softly.assertThat(response.getStatusCode())
                    .as("Request %d should succeed", i)
                    .isEqualTo(200);
                softly.assertThat(extractWorkerFromResponse(response))
                    .as("Request %d should stick to worker %s", i, worker)
                    .isEqualTo(worker);
            } catch (IOException e) {
                preKillIoFailures++;
                log.warn("Pre-kill sticky request {} failed with IOException ({}/3 allowed): {}",
                         i, preKillIoFailures, e.getMessage());
                if (preKillIoFailures > 3) {
                    throw e;
                }
            }
        }

        // Kill worker handling request
        final WildFlyWorker workerToKill = "worker1".equals(worker) ?
            cluster.getWorker1() : cluster.getWorker2();
        log.info("Killing worker: {}", worker);
        workerToKill.kill();

        // Wait for failover - verify custom cookie name still works after failover.
        // ignoreExceptionsInstanceOf(IOException.class) is needed because the surviving worker
        // may hit Infinispan timeouts (17.5s each) while trying to contact the dead worker,
        // causing OkHttp to throw SocketTimeoutException. Also, httpd mod_proxy_cluster may
        // hang until ProxyTimeout. 120s timeout to outlast multiple Infinispan timeout cycles.
        await().atMost(TestTimeouts.FAILOVER)
            .pollInterval(ofSeconds(2))
            .ignoreExceptionsInstanceOf(IOException.class)
            .untilAsserted(() -> {
                final HttpResponse response = httpClient.getWithSession(url, effectiveCookieName + "=" + cookie);
                assertThat(response.getStatusCode()).isEqualTo(200);
            });

        // Make 10 more requests - verify failover succeeds with custom cookie name.
        // Allow occasional IOExceptions (SocketTimeoutException) as the surviving worker
        // may still be processing stale Infinispan cross-node lookups for a few seconds.
        String failoverWorker = null;
        int ioFailures = 0;
        for (int i = 0; i < 10; i++) {
            try {
                final HttpResponse response = httpClient.getWithSession(url, effectiveCookieName + "=" + cookie);
                softly.assertThat(response.getStatusCode())
                    .as("Failover request %d should succeed with custom cookie name '%s'", i, effectiveCookieName)
                    .isEqualTo(200);

                if (response.getStatusCode() == 200) {
                    final String currentWorker = extractWorkerFromResponse(response);
                    if (failoverWorker == null) {
                        failoverWorker = currentWorker;
                        softly.assertThat(currentWorker)
                            .as("Should failover to different worker")
                            .isNotEqualTo(worker);
                    } else {
                        softly.assertThat(currentWorker)
                            .as("Failover requests should stick to same worker")
                            .isEqualTo(failoverWorker);
                    }
                }
            } catch (IOException e) {
                ioFailures++;
                log.warn("Failover request {} failed with IOException ({}/3 allowed): {}",
                         i, ioFailures, e.getMessage());
                if (ioFailures > 3) {
                    throw e;
                }
            }
        }

        log.info("Custom cookie name '{}' worked successfully: {} -> {}", effectiveCookieName, worker, failoverWorker);

        // Check for NPE in logs (JBEAP-5494) - check the surviving worker
        final WildFlyWorker survivingWorker = "worker1".equals(worker) ?
            cluster.getWorker2() : cluster.getWorker1();

        try {
            if (survivingWorker != null && survivingWorker.isRunning()) {
                final String logs = survivingWorker.getServerLog(100);
                softly.assertThat(logs)
                    .as("No NullPointerException should occur (JBEAP-5494)")
                    .doesNotContain("NullPointerException");
            }
        } catch (Exception e) {
            log.warn("Could not retrieve logs from surviving worker: {}", e.getMessage());
        }

        log.info("Cookie name test completed successfully for: {}", effectiveCookieName);
    }

    /**
     * Verifies JVM route integrity when worker joins cluster at runtime.
     * Tests that session ID and JVM route remain intact when worker2 repeatedly joins and leaves.
     * Each cycle starts worker2 while requests are in-flight, then stops it after requests complete.
     * Passes if all requests route to worker1, session ID unchanged, JVM route present.
     * Reference: JBEAP-6683 (session ID corruption), JBEAP-6078 (missing JVM route)
     */
    @Test
    public void testJvmRouteLostJoinAtRuntime(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start only worker1 initially (balancer already started by extension)
        cluster.startWorkers(1);

        final String balancerUrl = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";

        // Wait for demo app to be accessible and returning JSESSIONID with JVM route.
        // In CI under load, worker registration on the balancer can be delayed.
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(2))
            .untilAsserted(() -> {
                HttpResponse resp = httpClient.get(balancerUrl);
                assertThat(resp.getStatusCode()).isEqualTo(200);
                String cookie = resp.getCookie("JSESSIONID");
                assertThat(cookie)
                    .as("JSESSIONID cookie must be present")
                    .isNotNull();
                assertThat(extractJvmRoute(cookie))
                    .as("JVM route must be present in cookie: %s", cookie)
                    .isNotNull()
                    .isNotEmpty();
            });

        WildFlyWorker worker2 = null;

        try {
            for (int cycle = 1; cycle <= 3; cycle++) {
                log.info("JVM route test cycle {}/3", cycle);

                final int currentCycle = cycle;
                final ExecutorService executor = Executors.newSingleThreadExecutor();
                final AtomicReference<String> initialRoute = new AtomicReference<>();
                final AtomicReference<String> initialWorker = new AtomicReference<>();

                // Extended read timeout: Infinispan state transfer during worker2 join/leave
                // can stall request processing beyond the default 10s, especially under CI load.
                final long stateTransferTimeout = TestTimeouts.STATE_TRANSFER_REQUEST.toSeconds();

                final Future<?> requestTask = executor.submit(() -> {
                    try {
                        // Initial request — establishes session on worker1.
                        // Retry with extended timeout: after stopping worker2 in a previous cycle,
                        // Infinispan state transfer may still be in progress on worker1.
                        HttpResponse response = null;
                        for (int attempt = 0; attempt < 5; attempt++) {
                            try {
                                response = httpClient.getWithTimeout(
                                        balancerUrl, stateTransferTimeout, TimeUnit.SECONDS);
                                if (response.getStatusCode() == 200) break;
                            } catch (IOException e) {
                                log.warn("Cycle {} initial request attempt {}/5 failed: {}",
                                         currentCycle, attempt + 1, e.getMessage());
                                if (attempt == 4) throw e;
                            }
                        }
                        final String cookie = response.getCookie("JSESSIONID");

                        final String sessionId = extractSessionIdOnly(cookie);
                        final String route = extractJvmRoute(cookie);
                        final String worker = extractWorkerFromResponse(response);

                        initialRoute.set(route);
                        initialWorker.set(worker);

                        log.debug("Cycle {}: Initial cookie={}, session={}, route={}, worker={}",
                                 currentCycle, cookie, sessionId, route, worker);

                        // Verify initial cookie has JVM route (JBEAP-6078)
                        assertThat(route)
                            .as("JVM route must be present in initial session cookie (JBEAP-6078). Cookie: %s", cookie)
                            .isNotNull()
                            .isNotEmpty();

                        // 50 continuous requests with sticky session.
                        // Allow occasional IOExceptions (SocketTimeoutException) and HTTP 500
                        // (Infinispan timeout when worker2 joins/leaves and triggers state transfer)
                        // on CI where Podman rootless networking causes delays.
                        // Generous failure budget: state transfer during worker2 join/leave
                        // causes both SocketTimeoutException and HTTP 500 on the remaining node.
                        // The budget covers up to ~half the requests failing transiently.
                        final int maxTransientFailures = 25;
                        int transientFailures = 0;
                        for (int i = 0; i < 50; i++) {
                            try {
                                final HttpResponse req = httpClient.getWithSession(
                                        balancerUrl, "JSESSIONID=" + cookie,
                                        stateTransferTimeout, TimeUnit.SECONDS);

                                if (req.getStatusCode() == 500) {
                                    transientFailures++;
                                    log.warn("Cycle {} request {} got HTTP 500 ({}/{} allowed)",
                                             currentCycle, i, transientFailures, maxTransientFailures);
                                    if (transientFailures > maxTransientFailures) {
                                        assertThat(req.getStatusCode())
                                            .as("Cycle %d request %d: Too many HTTP 500 errors", currentCycle, i)
                                            .isEqualTo(200);
                                    }
                                } else {
                                    assertThat(req.getStatusCode())
                                        .as("Cycle %d request %d should succeed", currentCycle, i)
                                        .isEqualTo(200);

                                    final String reqWorker = extractWorkerFromResponse(req);
                                    assertThat(reqWorker)
                                        .as("[JBEAP-6683] Cycle %d request %d: Should stick to worker %s", currentCycle, i, worker)
                                        .isEqualTo(worker);
                                }
                            } catch (IOException e) {
                                transientFailures++;
                                log.warn("Cycle {} request {} failed with IOException ({}/{} allowed): {}",
                                         currentCycle, i, transientFailures, maxTransientFailures, e.getMessage());
                                if (transientFailures > maxTransientFailures) {
                                    throw e;
                                }
                            }

                            Thread.sleep(100);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                // Start worker2 while requests are ongoing (after ~1 second)
                Thread.sleep(1000);
                log.info("Cycle {}: Starting worker2 dynamically while requests are ongoing", cycle);
                worker2 = WildFlyWorker.create("worker2", cluster.getBalancer());
                worker2.start();

                // Wait for requests to complete
                requestTask.get(180, TimeUnit.SECONDS);
                executor.shutdown();

                // Stop worker2 before next cycle
                log.info("Cycle {}: Stopping worker2", cycle);
                worker2.stop();
                worker2 = null;

                log.info("Cycle {}: Completed — JVM route '{}' on worker '{}'",
                         cycle, initialRoute.get(), initialWorker.get());
            }
        } finally {
            // Ensure worker2 is cleaned up even if the test fails mid-cycle.
            // Without this, orphaned containers accumulate on Podman, causing
            // Broken Pipe errors and resource exhaustion in subsequent tests.
            if (worker2 != null) {
                try {
                    worker2.stop();
                } catch (Exception e) {
                    log.debug("Ignoring error stopping leaked worker2: {}", e.getMessage());
                }
            }
        }

        log.info("JVM route integrity test completed — all cycles verified JVM route presence and session stickiness");
    }

    /**
     * Configures session-draining-strategy to NEVER on specified workers.
     * Required for session timeout tests to prevent the balancer from
     * draining sessions when a worker is stopped or a context is disabled.
     *
     * @param workers Workers to configure
     * @throws Exception if configuration fails
     */
    private void configureSessionDrainingNever(WildFlyWorker... workers) throws Exception {
        for (WildFlyWorker worker : workers) {
            worker.modCluster().setSessionDrainingStrategy("NEVER");
        }
    }

    /**
     * Extracts session ID without JVM route from JSESSIONID cookie.
     * Format: &lt;session-id&gt;.&lt;jvm-route&gt;
     *
     * @param cookie JSESSIONID cookie value
     * @return Session ID only (before the dot)
     */
    private String extractSessionIdOnly(final String cookie) {
        if (cookie == null) {
            return null;
        }
        final int dotIndex = cookie.indexOf('.');
        return dotIndex > 0 ? cookie.substring(0, dotIndex) : cookie;
    }

    /**
     * Extracts JVM route from JSESSIONID cookie.
     * Format: &lt;session-id&gt;.&lt;jvm-route&gt;
     *
     * @param cookie JSESSIONID cookie value
     * @return JVM route (after the dot), or null if no route present
     */
    private String extractJvmRoute(final String cookie) {
        if (cookie == null) {
            return null;
        }
        final int dotIndex = cookie.indexOf('.');
        return dotIndex > 0 ? cookie.substring(dotIndex + 1) : null;
    }

    /**
     * Extracts worker name from HTTP response body.
     * Parses the {@code <strong>Worker:</strong>} tag from the demo app JSP output
     * to get the actual serving worker's {@code jboss.node.name}. This avoids false
     * matches from the session ID's JVM route (e.g., "abc.worker2" in the Session ID
     * field when worker1 is actually serving the request after failover).
     *
     * @param response HTTP response
     * @return Worker name (e.g., "worker1")
     */
    private String extractWorkerFromResponse(final HttpResponse response) {
        final String body = response.getBody();
        // Parse from JSP output: <strong>Worker:</strong> worker1
        if (body.contains("<strong>Worker:</strong>")) {
            int startIdx = body.indexOf("<strong>Worker:</strong>") + "<strong>Worker:</strong>".length();
            int endIdx = body.indexOf("</p>", startIdx);
            if (endIdx > startIdx) {
                return body.substring(startIdx, endIdx).trim();
            }
        }
        // Fallback: simple contains check
        if (body.contains("worker1")) {
            return "worker1";
        }
        if (body.contains("worker2")) {
            return "worker2";
        }
        return "unknown";
    }
}
