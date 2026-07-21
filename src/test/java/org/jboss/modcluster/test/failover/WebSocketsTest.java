package org.jboss.modcluster.test.failover;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.jboss.modcluster.test.apps.WebSocketAppBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests for WebSocket connections through the mod_cluster balancer.
 * Verifies that WebSocket connections are established, data is transmitted,
 * and failover works correctly when a worker dies.
 */
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class WebSocketsTest {

    private static final Logger log = LoggerFactory.getLogger(WebSocketsTest.class);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that a WebSocket connection can be established through the balancer.
     * Passes if the client receives the CONNECTED message with a valid worker name.
     */
    @Test
    public void testWebSocketInitialConnection(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        disableHttp2IfNeeded(cluster, cluster.getWorker1());
        deployWebSocketApp(cluster.getWorker1());

        final String wsUrl = getWebSocketUrl(cluster);

        // Wait for the HTTP endpoint to be fully accessible (200, not 404)
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse resp = httpClient.get(cluster.getBalancer().getHttpUrl() + "/ws-echo/");
                    assertThat(resp.getStatusCode()).isEqualTo(200);
                });

        log.info("WebSocket app deployed and accessible, connecting to: {}", wsUrl);

        final OkHttpClient wsClient = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        final Request request = new Request.Builder().url(wsUrl).build();
        final WebSocket ws = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                log.info("WebSocket received: {}", text);
                receivedMessages.add(text);
                if (text.startsWith("CONNECTED:")) {
                    connectedLatch.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("WebSocket failure: {} (response: {})", t.getMessage(),
                        response != null ? response.code() : "null");
                errors.add(t);
                connectedLatch.countDown();
            }
        });

        try {
            boolean connected = connectedLatch.await(15, TimeUnit.SECONDS);

            softly.assertThat(connected)
                    .as("WebSocket connection should be established within 15 seconds")
                    .isTrue();

            softly.assertThat(errors)
                    .as("No WebSocket errors should occur during connection")
                    .isEmpty();

            if (!receivedMessages.isEmpty()) {
                softly.assertThat(receivedMessages.get(0))
                        .as("Initial message should contain worker name")
                        .startsWith("CONNECTED:")
                        .containsPattern("worker\\d+");
            }
        } finally {
            ws.close(1000, "Test complete");
        }
    }

    /**
     * Verifies that continuous data transmission works over a WebSocket connection through the balancer.
     * Sends multiple messages and verifies each is echoed back correctly.
     *
     * Passes if all 10 messages are echoed back with correct worker identification.
     */
    @Test
    public void testWebSocketContinuousTransmission(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        disableHttp2IfNeeded(cluster, cluster.getWorker1());
        deployWebSocketApp(cluster.getWorker1());

        final String wsUrl = getWebSocketUrl(cluster);

        // Wait for app to be accessible
        await().atMost(TestTimeouts.CLUSTER_FORMATION).pollInterval(ofSeconds(2))
                .untilAsserted(() -> {
                    HttpResponse resp = httpClient.get(cluster.getBalancer().getHttpUrl() + "/ws-echo/");
                    assertThat(resp.getStatusCode()).isEqualTo(200);
                });

        final OkHttpClient wsClient = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final int messageCount = 10;
        final CountDownLatch allReceived = new CountDownLatch(messageCount + 1); // +1 for CONNECTED
        final List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        final Request request = new Request.Builder().url(wsUrl).build();
        final WebSocket ws = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                log.debug("WebSocket received: {}", text);
                receivedMessages.add(text);
                allReceived.countDown();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("WebSocket failure: {}", t.getMessage());
                errors.add(t);
            }
        });

        try {
            // Wait for connection to establish
            Thread.sleep(2000);

            // Send messages
            for (int i = 0; i < messageCount; i++) {
                final String message = "test-message-" + i;
                boolean sent = ws.send(message);
                softly.assertThat(sent)
                        .as("Message %d should be sent successfully", i)
                        .isTrue();
                Thread.sleep(100); // Small delay between messages
            }

            // Wait for all responses
            boolean allDone = allReceived.await(30, TimeUnit.SECONDS);
            softly.assertThat(allDone)
                    .as("All %d messages should be echoed back within 30 seconds", messageCount)
                    .isTrue();

            softly.assertThat(errors)
                    .as("No WebSocket errors during continuous transmission")
                    .isEmpty();

            // Verify echo messages contain correct format
            long echoCount = receivedMessages.stream()
                    .filter(msg -> msg.startsWith("ECHO:"))
                    .count();
            softly.assertThat(echoCount)
                    .as("Should receive %d echo responses", messageCount)
                    .isEqualTo(messageCount);

            log.info("Continuous WebSocket transmission test: sent {}, received {} messages",
                    messageCount, receivedMessages.size());
        } finally {
            ws.close(1000, "Test complete");
        }
    }

    /**
     * Verifies WebSocket failover when a worker dies during an active WebSocket session.
     * Establishes a WebSocket connection, kills the handling worker, and verifies
     * that the connection is properly closed (onFailure or onClosing is triggered).
     *
     * Passes if the WebSocket connection failure is detected after killing the worker.
     */
    @Test
    public void testWebSocketFailover(TestCluster cluster, HttpClient httpClient) throws Exception {
        cluster.startWorkers(2);
        disableHttp2IfNeeded(cluster, cluster.getWorker1());
        disableHttp2IfNeeded(cluster, cluster.getWorker2());
        deployWebSocketApp(cluster.getWorker1());
        deployWebSocketApp(cluster.getWorker2());

        final String wsUrl = getWebSocketUrl(cluster);

        // Wait for both workers' ws-echo contexts to register on the balancer.
        // A single HTTP 200 only confirms one worker; the WebSocket upgrade can
        // be routed to the other worker whose context isn't registered yet.
        String wsEchoUrl = cluster.getBalancer().getHttpUrl() + "/ws-echo/";
        httpClient.waitForWorkerRegistration(wsEchoUrl, 2, TestTimeouts.CLUSTER_FORMATION);

        final OkHttpClient wsClient = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();

        final CountDownLatch connectedLatch = new CountDownLatch(1);
        final CountDownLatch failureLatch = new CountDownLatch(1);
        final List<String> receivedMessages = Collections.synchronizedList(new ArrayList<>());
        final StringBuilder connectedWorker = new StringBuilder();

        final Request request = new Request.Builder().url(wsUrl).build();
        final WebSocket ws = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                log.info("WebSocket received: {}", text);
                receivedMessages.add(text);
                if (text.startsWith("CONNECTED:")) {
                    connectedWorker.append(text.substring("CONNECTED:".length()));
                    connectedLatch.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.info("WebSocket failure detected (expected after kill): {}", t.getMessage());
                failureLatch.countDown();
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                log.info("WebSocket closing: {} - {}", code, reason);
                failureLatch.countDown();
            }
        });

        try {
            // Wait for connection
            boolean connected = connectedLatch.await(15, TimeUnit.SECONDS);
            softly.assertThat(connected)
                    .as("WebSocket connection should be established")
                    .isTrue();

            final String workerName = connectedWorker.toString();
            log.info("WebSocket connected to worker: {}", workerName);

            // Send a message to verify the connection works
            ws.send("pre-failover-message");
            Thread.sleep(1000);

            softly.assertThat(receivedMessages)
                    .as("Should receive echo before failover")
                    .anyMatch(msg -> msg.contains("pre-failover-message"));

            // Kill the worker handling the WebSocket connection
            if ("worker1".equals(workerName)) {
                log.info("Killing worker1 (WebSocket handler)...");
                cluster.getWorker1().kill();
            } else {
                log.info("Killing worker2 (WebSocket handler)...");
                cluster.getWorker2().kill();
            }

            // Wait for WebSocket connection to detect the failure
            boolean failureDetected = failureLatch.await(30, TimeUnit.SECONDS);
            softly.assertThat(failureDetected)
                    .as("WebSocket connection failure should be detected after killing worker")
                    .isTrue();

            log.info("WebSocket failover test completed - connection failure properly detected");

        } finally {
            ws.close(1000, "Test complete");
        }
    }

    /**
     * Disable HTTP/2 on a worker if the balancer is Undertow.
     * The h2c issue only affects Undertow balancer connections to workers.
     * httpd uses HTTP/1.1 to backends, so no h2c conflict occurs.
     *
     * @param cluster the test cluster (to check balancer type)
     * @param worker the WildFly worker to conditionally configure
     * @throws Exception if the configuration or reload fails
     */
    private void disableHttp2IfNeeded(final TestCluster cluster, final WildFlyWorker worker) throws Exception {
        if (cluster.getBalancer().getType() == BalancerType.UNDERTOW) {
            disableHttp2OnWorker(worker);
        }
    }

    /**
     * Disable HTTP/2 on a worker's default HTTP listener and reload.
     * HTTP/2 connections do not support HTTP/1.1 Upgrade, which is required for WebSocket.
     * The balancer's mod_cluster proxy connects to workers via HTTP; if that connection
     * upgrades to HTTP/2 (h2c), WebSocket handshake fails with UT000077.
     *
     * @param worker the WildFly worker to configure
     * @throws Exception if the configuration or reload fails
     */
    private void disableHttp2OnWorker(final WildFlyWorker worker) throws Exception {
        worker.undertow().setHttpListenerEnableHttp2("default-server", "default", false);
        worker.reload();
        log.info("HTTP/2 disabled on worker '{}' for WebSocket support", worker.getName());
    }

    /**
     * Deploy the WebSocket echo application to a worker.
     * Creates a WAR containing the EchoWebSocketEndpoint using ShrinkWrap,
     * with a web.xml to ensure proper annotation scanning.
     *
     * @param worker the WildFly worker to deploy to
     * @throws Exception if deployment fails
     */
    private void deployWebSocketApp(WildFlyWorker worker) throws Exception {
        final File warFile = WebSocketAppBuilder.createWebSocketApp();
        worker.deployment().deploy(warFile, "ws-echo.war");
        log.info("Deployed WebSocket echo app to worker '{}'", worker.getName());
    }

    /**
     * Get the WebSocket URL through the balancer.
     *
     * @param cluster the test cluster
     * @return the WebSocket URL
     */
    private String getWebSocketUrl(TestCluster cluster) {
        final String httpUrl = cluster.getBalancer().getHttpUrl();
        return httpUrl.replace("http://", "ws://") + "/ws-echo/echo";
    }
}
