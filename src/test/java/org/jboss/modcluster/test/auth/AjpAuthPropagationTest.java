package org.jboss.modcluster.test.auth;

import org.jboss.modcluster.test.apps.SecuredAppBuilder;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.base.SkipModProxyCluster;
import org.jboss.modcluster.test.utils.HttpClient;
import org.jboss.modcluster.test.utils.HttpClient.HttpResponse;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;

import java.io.File;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests REMOTE_USER authentication propagation via AJP from httpd to WildFly/Elytron.
 *
 * <p>Validates the end-to-end path: httpd authenticates the user via Basic auth
 * → sets {@code REMOTE_USER} → {@code mod_proxy_ajp} forwards it as the AJP
 * {@code remote_user} attribute → Undertow receives it → Elytron's EXTERNAL mechanism
 * authenticates the user → the secured servlet is accessible.</p>
 *
 * <p>Uses a direct {@code ProxyPass ajp://} to the worker's AJP port, which is the same
 * protocol path used by IIS/isapi_redirect after Windows authentication.</p>
 */
@Tag("native")
@SkipModProxyCluster
@ExtendWith(ModClusterTestExtension.class)
public class AjpAuthPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(AjpAuthPropagationTest.class);

    private static final int AJP_BASE_PORT = 8019;
    private static final int AJP_PORT = 8119; // base + worker1 offset (100)
    private static final String AJP_SOCKET_BINDING = "ajp-test";
    private static final String AJP_LISTENER = "ajp-test-listener";

    /**
     * Verifies that a user with a valid REMOTE_USER and the correct Elytron role
     * can access a secured servlet through the balancer via AJP.
     */
    @Test
    public void testAuthenticatedUserCanAccessSecuredServlet(final TestCluster cluster,
                                                             final HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();

        configurator.configureBalancerRemoteUser(cluster.getBalancer(), "testuser", AJP_PORT);

        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        configurator.configureWorker(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        addAjpListener(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deployment().deploy(securedWar);

        String url = cluster.getBalancer().getHttpUrl() + "/secured/secured";
        awaitAjpAvailable(httpClient, url);

        HttpResponse response = httpClient.get(url, basicAuthHeaders("testuser", "password"));

        log.info("Response: status={}, body={}", response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("user=testuser");
    }

    /**
     * Verifies that a request without REMOTE_USER results in the EXTERNAL mechanism
     * having no principal, and the secured servlet rejects the request with 403.
     */
    @Test
    public void testNoRemoteUserIsRejected(final TestCluster cluster,
                                            final HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();

        // ProxyPass without Basic auth — no REMOTE_USER in AJP
        configurator.configureBalancerRemoteUser(cluster.getBalancer(), null, AJP_PORT);

        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        configurator.configureWorker(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"));
        addAjpListener(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deployment().deploy(securedWar);

        String url = cluster.getBalancer().getHttpUrl() + "/secured/secured";
        awaitAjpAvailable(httpClient, url);

        HttpResponse response = httpClient.get(url);

        log.info("Response (no REMOTE_USER): status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(403);
    }

    /**
     * Verifies that a user who exists in the Elytron realm but does not have
     * the required role is rejected with 403.
     */
    @Test
    public void testUnauthorizedUserIsRejected(final TestCluster cluster,
                                                final HttpClient httpClient) throws Exception {
        AjpAuthConfigurator configurator = new AjpAuthConfigurator();

        configurator.configureBalancerRemoteUser(cluster.getBalancer(), "baduser", AJP_PORT);

        cluster.startWorkers(1);
        WildFlyWorker worker = cluster.getWorker1();

        configurator.configureWorker(worker,
                new AjpAuthConfigurator.UserEntry("testuser", "gooduser"),
                new AjpAuthConfigurator.UserEntry("baduser", "badrole"));
        addAjpListener(worker);

        File securedWar = SecuredAppBuilder.createSecuredApp();
        worker.deployment().deploy(securedWar);

        String url = cluster.getBalancer().getHttpUrl() + "/secured/secured";
        awaitAjpAvailable(httpClient, url);

        HttpResponse response = httpClient.get(url, basicAuthHeaders("baduser", "password"));

        log.info("Response (wrong role): status={}", response.getStatusCode());
        assertThat(response.getStatusCode()).isEqualTo(403);
    }

    private void addAjpListener(WildFlyWorker worker) throws Exception {
        Operations ops = worker.getOperations();
        Address sbAddr = Address.of("socket-binding-group", "standard-sockets")
                .and("socket-binding", AJP_SOCKET_BINDING);
        if (!ops.exists(sbAddr)) {
            worker.undertow().addSocketBinding(AJP_SOCKET_BINDING, AJP_BASE_PORT);
        }
        Address listenerAddr = Address.subsystem("undertow")
                .and("server", "default-server")
                .and("ajp-listener", AJP_LISTENER);
        if (!ops.exists(listenerAddr)) {
            worker.undertow().addAjpListener(AJP_LISTENER, "default-server", AJP_SOCKET_BINDING);
            worker.reload();
        }
        log.info("AJP listener on port {} ready", AJP_PORT);
    }

    private void awaitAjpAvailable(HttpClient httpClient, String url) {
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(2))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    HttpResponse response = httpClient.get(url);
                    assertThat(response.getStatusCode()).isLessThan(500);
                });
        log.info("AJP proxy responding at {}", url);
    }

    private static Map<String, String> basicAuthHeaders(String username, String password) {
        String credentials = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes());
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Basic " + credentials);
        return headers;
    }
}
