package org.jboss.modcluster.test.ajp;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.NativePortAllocator;
import org.jboss.modcluster.test.utils.TestMode;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jboss.modcluster.test.utils.WildFlyDeploymentManager.DEMO_APP;

/**
 * Tests mod_cluster communication with backend workers over the AJP protocol.
 *
 * <p>By default, mod_cluster registers the worker's HTTP listener with the balancer
 * and all proxied traffic flows over HTTP. This test reconfigures the mod_cluster
 * subsystem to register an AJP listener instead, verifying the full AJP data path:
 * client &rarr; httpd &rarr; {@code mod_proxy_ajp} &rarr; Undertow AJP listener &rarr; response.</p>
 *
 * <p>Requires the httpd balancer because Undertow-based balancers do not support
 * AJP backend connections (the {@code @Tag("httpd")} annotation causes this test
 * to be skipped when {@code -Dbalancer.type=undertow}).</p>
 *
 * <h3>AJP secret enforcement</h3>
 * <p>Since UNDERTOW-2791, Undertow enforces
 * {@code -Dio.undertow.ajp.REQUIRE_AJP_SECRET=true} by default. The secret
 * check in {@code AjpReadListener} runs before the packet-type dispatch, so
 * CPING health checks (which carry no secret attribute) are rejected with 403
 * before {@code handleCPing()} is reached. Since {@code mod_proxy_ajp} sends
 * CPING before every request by default, this breaks all AJP traffic through
 * mod_cluster. The test disables enforcement via
 * {@code io.undertow.ajp.REQUIRE_AJP_SECRET=false} as a workaround.</p>
 *
 * <h3>Relationship to noe-tests</h3>
 * <p>Ported from noe-tests {@code ModClusterAJP.groovy}. The original test also
 * validates AJP secret matching ({@code AJPSecret} directive vs. Tomcat's
 * {@code secretRequired}/{@code secret} connector attributes). Undertow now
 * supports AJP secret validation (UNDERTOW-2791), but the secret tests cannot
 * be implemented here until the CPING bug described above is fixed &mdash; the
 * worker and balancer ({@code AJPSecret} in {@code mod_manager}) both support
 * the secret, but CPING breaks the connection before any request is sent.</p>
 *
 * @see org.jboss.modcluster.test.utils.WildFlyUndertowManager#addAjpListener(String, String, String, int)
 * @see org.jboss.modcluster.test.utils.WildFlyModClusterManager#setListener(String)
 */
@Tag("httpd")
@ExtendWith(ModClusterTestExtension.class)
public class ModClusterAjpTest {

    private static final Logger log = LoggerFactory.getLogger(ModClusterAjpTest.class);

    private static final int AJP_PORT = 8009;
    private static final String AJP_LISTENER = "ajp";
    private static final String AJP_SOCKET_BINDING = "ajp";

    /**
     * Verifies that mod_cluster registers the worker over AJP and that HTTP requests
     * are correctly proxied through the AJP data path.
     *
     * <p>The test adds an AJP listener on port {@value AJP_PORT}, switches the
     * mod_cluster proxy's {@code listener} attribute from {@code "default"} (HTTP)
     * to {@code "ajp"}, and waits for the worker to re-register. It then asserts
     * that the balancer's MCMP INFO reports the worker with {@code Type: ajp} and
     * URI {@code ajp://<host>:8009}, and finally sends an HTTP request through the
     * balancer to confirm a 200 response over the AJP data path.</p>
     *
     * <p>Passes if the balancer's MCMP INFO shows {@code ajp://} scheme and the
     * proxied request returns HTTP 200.</p>
     */
    @Test
    public void testTrafficFlowsThroughAjp(final TestCluster cluster,
                                           final HttpClient httpClient) throws Exception {
        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        worker.undertow().addAjpListener(AJP_LISTENER, "default-server", AJP_SOCKET_BINDING, AJP_PORT);
        worker.reload();
        worker.modCluster().setListener(AJP_LISTENER);

        log.info("Waiting for worker to re-register with AJP scheme on balancer");
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, ModelNode> workers = cluster.getBalancer().getWorkerInfo();
                    assertThat(workers).containsKey(worker.getName());
                    URI uri = new URI(workers.get(worker.getName()).get("uri").asString());
                    assertThat(uri.getScheme()).isEqualTo("ajp");
                    int expectedPort = TestMode.current().isNative()
                            ? AJP_PORT + NativePortAllocator.offset(worker.getName())
                            : AJP_PORT;
                    assertThat(uri.getPort()).isEqualTo(expectedPort);
                });

        URI registeredUri = new URI(cluster.getBalancer().getWorkerInfo()
                .get(worker.getName()).get("uri").asString());
        log.info("Worker registered as: {}", registeredUri);

        worker.deployment().deployDemoApp();

        String url = cluster.getBalancer().getHttpUrl() + "/" + DEMO_APP + "/";
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(2))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(url);
                    assertThat(response.getStatusCode()).isEqualTo(200);
                });

        HttpResponse response = httpClient.get(url);
        log.info("Response via AJP: status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

}
