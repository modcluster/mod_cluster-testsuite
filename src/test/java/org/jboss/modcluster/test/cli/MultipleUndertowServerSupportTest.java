package org.jboss.modcluster.test.cli;

import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.base.ModClusterTestExtension;
import org.jboss.modcluster.test.base.ModClusterTestExtension.TestCluster;
import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.jboss.modcluster.test.utils.NativePortAllocator;
import org.jboss.modcluster.test.utils.TestMode;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.jboss.modcluster.test.utils.WildFlyWorker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.ModelNodeResult;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * Tests support for multiple mod_cluster proxy configurations with listeners on non-default Undertow servers.
 * Verifies that WildFly can register with multiple balancers using independent proxy configurations,
 * and that proxy attribute changes are isolated from each other.
 * Undertow-only: these tests rely on Undertow server architecture (creating secondary servers/listeners).
 *
 * @see <a href="https://issues.jboss.org/browse/WFLY-6803">WFLY-6803</a>
 */
@Tag("undertow")
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class MultipleUndertowServerSupportTest {

    private static final Logger log = LoggerFactory.getLogger(MultipleUndertowServerSupportTest.class);

    private static final int SECOND_LISTENER_PORT = 8568;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Verifies that a proxy configuration can use a listener from a non-default Undertow server.
     * Creates a second Undertow server with an HTTP listener, assigns it to the default proxy,
     * and verifies the worker registers with the expected scheme and port.
     * Passes if the worker URI uses HTTP scheme with the second listener's port.
     */
    @Test
    public void testSettingListenerFromNonDefaultUndertowServer(final TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        final WildFlyWorker worker = cluster.getWorker1();
        final Balancer balancer = cluster.getBalancer();

        final String secondServerName = "second-server-" + randomSuffix();
        final String socketBindingName = "second-socket-" + randomSuffix();
        final String httpListenerName = "secondListener-" + randomSuffix();
        final Address defaultModClusterProxy = Address.subsystem("modcluster").and("proxy", "default");

        // Wait for initial worker registration
        log.info("Waiting for worker to register with balancer");
        await().atMost(TestTimeouts.CLUSTER_FORMATION)
                .pollInterval(ofSeconds(5))
                .untilAsserted(() -> {
                    Map<String, ModelNode> workers = balancer.getWorkerInfo();
                    assertThat(workers).isNotEmpty();
                });

        String defaultListenerName = null;
        try {
            // Save current listener value
            Operations ops = worker.getOperations();
            defaultListenerName = ops.readAttribute(defaultModClusterProxy, "listener").stringValue();
            log.info("Default listener name: {}", defaultListenerName);

            // Create second Undertow server + socket binding + HTTP listener
            worker.undertow().addServer(secondServerName);
            worker.undertow().addSocketBinding(socketBindingName, SECOND_LISTENER_PORT);
            worker.undertow().addHttpListener(httpListenerName, secondServerName, socketBindingName);

            // Set listener on default proxy to the new listener
            ops.writeAttribute(defaultModClusterProxy, "listener", httpListenerName).assertSuccess();
            log.info("Set default proxy listener to '{}'", httpListenerName);

            // Reload without reconfiguring static proxy (preserves the listener change)
            worker.reloadServer();

            // Wait for worker to register under the new server name
            final String expectedNodeName = worker.getName() + "-" + secondServerName;
            log.info("Waiting for worker to register as '{}'", expectedNodeName);

            await().atMost(TestTimeouts.CLUSTER_FORMATION)
                    .pollInterval(ofSeconds(5))
                    .untilAsserted(() -> {
                        Map<String, ModelNode> workers = balancer.getWorkerInfo();
                        assertThat(workers).containsKey(expectedNodeName);
                    });

            // Verify worker URI has expected scheme and port
            Map<String, ModelNode> workerInfo = balancer.getWorkerInfo();
            ModelNode nodeInfo = workerInfo.get(expectedNodeName);
            URI workerUri = new URI(nodeInfo.get("uri").asString());

            log.info("Worker URI: {}", workerUri);
            softly.assertThat(workerUri.getScheme())
                    .as("Worker URI should use HTTP scheme")
                    .isEqualTo("http");
            int expectedPort = TestMode.current().isNative()
                    ? SECOND_LISTENER_PORT + NativePortAllocator.offset(worker.getName())
                    : SECOND_LISTENER_PORT;
            softly.assertThat(workerUri.getPort())
                    .as("Worker URI should use the second listener's port")
                    .isEqualTo(expectedPort);

        } finally {
            // Restore original listener and remove second server
            Operations ops = worker.getOperations();
            if (defaultListenerName != null) {
                ops.writeAttribute(defaultModClusterProxy, "listener", defaultListenerName);
                worker.getAdministration().reloadIfRequired();
            }
            worker.undertow().removeServer(secondServerName);
            worker.getAdministration().reloadIfRequired();
        }
    }

    /**
     * Verifies that two proxy configurations connect to different balancers independently.
     * Creates a second balancer on the same network, a second Undertow server with an AJP listener,
     * and a second mod_cluster proxy pointing to the second balancer.
     * Passes if balancer1 sees the worker on AJP:8009 and balancer2 sees it on AJP:{@value SECOND_LISTENER_PORT}.
     */
    @Test
    public void testRegisterOneNodeWithTwoBalancers(final TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        final WildFlyWorker worker = cluster.getWorker1();
        final Balancer balancer1 = cluster.getBalancer();

        final String secondServerName = "second-server-" + randomSuffix();
        final String socketBindingName = "second-socket-" + randomSuffix();
        final String ajpListenerName = "secondListener-" + randomSuffix();
        final String outboundSocketName = "modcluster-balancer2";
        final String secondProxyName = "second-proxy-" + randomSuffix();

        Balancer balancer2 = Balancer.create(BalancerType.UNDERTOW);

        try {
            // Start second balancer on the same network
            balancer2.startOnSameNetworkAs(balancer1, "balancer2");
            log.info("Second balancer started: {}", balancer2.getHttpUrl());

            // Create second Undertow server + AJP listener on worker
            worker.undertow().addServer(secondServerName);
            worker.undertow().addAjpListener(ajpListenerName, secondServerName, socketBindingName, SECOND_LISTENER_PORT);

            // Create outbound-socket-binding pointing to balancer2
            Operations ops = worker.getOperations();
            Address outboundSocketAddr = Address.of("socket-binding-group", "standard-sockets")
                    .and("remote-destination-outbound-socket-binding", outboundSocketName);

            ModelNode addSocketBinding = new ModelNode();
            ModelNode address = addSocketBinding.get("address");
            address.add("socket-binding-group", "standard-sockets");
            address.add("remote-destination-outbound-socket-binding", outboundSocketName);
            addSocketBinding.get("operation").set("add");
            addSocketBinding.get("host").set(balancer2.getProxyHost());
            int balancer2McmpPort = balancer2.getInternalMcmpPort();
            addSocketBinding.get("port").set(balancer2McmpPort);

            worker.getManagementClient().execute(addSocketBinding);
            log.info("Created outbound-socket-binding '{}' -> {}:{}", outboundSocketName,
                    balancer2.getProxyHost(), balancer2McmpPort);

            // Create second mod_cluster proxy with listener and proxies list
            Address secondProxyAddr = Address.subsystem("modcluster").and("proxy", secondProxyName);
            ModelNode proxiesList = new ModelNode();
            proxiesList.add(outboundSocketName);

            ops.add(secondProxyAddr, Values.of("listener", ajpListenerName)
                    .and("proxies", proxiesList))
                    .assertSuccess("Adding second mod_cluster proxy failed");
            log.info("Created second mod_cluster proxy '{}' with listener '{}'", secondProxyName, ajpListenerName);

            // Reload to apply changes
            worker.reloadServer();

            // Verify balancer1 sees worker with AJP:8009
            log.info("Waiting for worker to register on balancer1");
            await().atMost(TestTimeouts.CLUSTER_FORMATION)
                    .pollInterval(ofSeconds(5))
                    .untilAsserted(() -> {
                        Map<String, ModelNode> workers = balancer1.getWorkerInfo();
                        assertThat(workers).containsKey(worker.getName());
                    });

            Map<String, ModelNode> balancer1Workers = balancer1.getWorkerInfo();
            ModelNode worker1Info = balancer1Workers.get(worker.getName());
            URI worker1Uri = new URI(worker1Info.get("uri").asString());

            log.info("Balancer1 worker URI: {}", worker1Uri);
            // Default proxy uses listener=default (HTTP on port 8080) configured by configureStaticProxy()
            softly.assertThat(worker1Uri.getScheme())
                    .as("Balancer1 worker should use HTTP scheme (default listener)")
                    .isEqualTo("http");
            int expectedHttpPort = TestMode.current().isNative()
                    ? NativePortAllocator.httpPort(worker.getName())
                    : 8080;
            softly.assertThat(worker1Uri.getPort())
                    .as("Balancer1 worker should use default HTTP port")
                    .isEqualTo(expectedHttpPort);

            // Verify balancer2 sees worker with AJP:SECOND_LISTENER_PORT
            final String expectedNodeName = worker.getName() + "-" + secondServerName;
            log.info("Waiting for worker to register on balancer2 as '{}'", expectedNodeName);
            await().atMost(TestTimeouts.CLUSTER_FORMATION)
                    .pollInterval(ofSeconds(5))
                    .untilAsserted(() -> {
                        Map<String, ModelNode> workers = balancer2.getWorkerInfo();
                        assertThat(workers).containsKey(expectedNodeName);
                    });

            Map<String, ModelNode> balancer2Workers = balancer2.getWorkerInfo();
            ModelNode worker2Info = balancer2Workers.get(expectedNodeName);
            URI worker2Uri = new URI(worker2Info.get("uri").asString());

            log.info("Balancer2 worker URI: {}", worker2Uri);
            softly.assertThat(worker2Uri.getScheme())
                    .as("Balancer2 worker should use AJP scheme")
                    .isEqualTo("ajp");
            int expectedAjpPort = TestMode.current().isNative()
                    ? SECOND_LISTENER_PORT + NativePortAllocator.offset(worker.getName())
                    : SECOND_LISTENER_PORT;
            softly.assertThat(worker2Uri.getPort())
                    .as("Balancer2 worker should use second listener's port")
                    .isEqualTo(expectedAjpPort);

        } finally {
            // Cleanup: remove second proxy, second server, socket bindings
            Operations ops = worker.getOperations();
            Address secondProxyAddr = Address.subsystem("modcluster").and("proxy", secondProxyName);
            ops.removeIfExists(secondProxyAddr);
            worker.undertow().removeServer(secondServerName);
            worker.getAdministration().reloadIfRequired();

            // Remove outbound socket binding
            Address outboundSocketAddr = Address.of("socket-binding-group", "standard-sockets")
                    .and("remote-destination-outbound-socket-binding", outboundSocketName);
            ops.removeIfExists(outboundSocketAddr);

            // Remove listener socket binding
            try {
                worker.undertow().removeSocketBinding(socketBindingName);
            } catch (Exception e) {
                log.debug("Ignoring error removing socket binding '{}': {}", socketBindingName, e.getMessage());
            }

            balancer2.stop();
        }
    }

    /**
     * Verifies that modifying a boolean attribute on one proxy does not affect another proxy.
     * Creates two additional mod_cluster proxies with independent listeners and checks that
     * toggling boolean attributes on one proxy leaves the other unchanged.
     * Passes if all boolean attributes remain independent across proxies.
     */
    @Test
    public void proxyConfigurationIndependence(final TestCluster cluster) throws Exception {
        cluster.startWorkers(1);
        final WildFlyWorker worker = cluster.getWorker1();

        final String secondServerName = "second-server-" + randomSuffix();
        final String secondSocketName = "second-socket-" + randomSuffix();
        final int secondSocketPort = 9586;
        final String secondListenerName = "secondListener-" + randomSuffix();
        final String secondProxyName = "secondProxy-" + randomSuffix();

        final String thirdSocketName = "third-socket-" + randomSuffix();
        final int thirdSocketPort = 9587;
        final String thirdListenerName = "thirdListener-" + randomSuffix();
        final String thirdProxyName = "thirdProxy-" + randomSuffix();

        final Address secondProxyAddr = Address.subsystem("modcluster").and("proxy", secondProxyName);
        final Address thirdProxyAddr = Address.subsystem("modcluster").and("proxy", thirdProxyName);

        final List<String> booleanAttributes = Arrays.asList(
                "advertise",
                "auto-enable-contexts",
                "sticky-session",
                "sticky-session-force",
                "sticky-session-remove"
        );

        try {
            Operations ops = worker.getOperations();

            // Create second Undertow server
            worker.undertow().addServer(secondServerName);

            // Create AJP listeners on the second server
            worker.undertow().addAjpListener(secondListenerName, secondServerName, secondSocketName, secondSocketPort);
            worker.undertow().addAjpListener(thirdListenerName, secondServerName, thirdSocketName, thirdSocketPort);

            // Create two additional mod_cluster proxies
            ops.add(secondProxyAddr, Values.of("listener", secondListenerName))
                    .assertSuccess("Adding second mod_cluster proxy failed");
            ops.add(thirdProxyAddr, Values.of("listener", thirdListenerName))
                    .assertSuccess("Adding third mod_cluster proxy failed");

            log.info("Created proxies '{}' and '{}'", secondProxyName, thirdProxyName);

            // Test independence for each boolean attribute
            for (String attrName : booleanAttributes) {
                // Read current value on second proxy
                ModelNodeResult originalResult = ops.readAttribute(secondProxyAddr, attrName);
                originalResult.assertSuccess();
                boolean originalValue = originalResult.booleanValue();

                // Toggle value on second proxy
                ops.writeAttribute(secondProxyAddr, attrName, !originalValue)
                        .assertSuccess("Writing attribute '" + attrName + "' on second proxy failed");

                worker.getAdministration().reloadIfRequired();

                // Verify third proxy's value is unchanged
                ModelNodeResult thirdResult = ops.readAttribute(thirdProxyAddr, attrName);
                thirdResult.assertSuccess();
                boolean thirdValue = thirdResult.booleanValue();

                softly.assertThat(thirdValue)
                        .as("Attribute '%s' on third proxy should remain unchanged after modifying second proxy",
                                attrName)
                        .isEqualTo(originalValue);

                log.info("Attribute '{}': secondProxy toggled from {} to {}, thirdProxy stayed at {}",
                        attrName, originalValue, !originalValue, thirdValue);
            }

        } finally {
            // Cleanup: remove proxies, second server, socket bindings
            Operations ops = worker.getOperations();
            ops.removeIfExists(secondProxyAddr);
            ops.removeIfExists(thirdProxyAddr);
            worker.getAdministration().reloadIfRequired();

            worker.undertow().removeServer(secondServerName);
            worker.getAdministration().reloadIfRequired();

            try {
                worker.undertow().removeSocketBinding(secondSocketName);
            } catch (Exception e) {
                log.debug("Ignoring error removing socket binding '{}': {}", secondSocketName, e.getMessage());
            }
            try {
                worker.undertow().removeSocketBinding(thirdSocketName);
            } catch (Exception e) {
                log.debug("Ignoring error removing socket binding '{}': {}", thirdSocketName, e.getMessage());
            }
        }
    }

    /**
     * Generate a short random suffix for resource names.
     *
     * @return 8-character random string
     */
    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
