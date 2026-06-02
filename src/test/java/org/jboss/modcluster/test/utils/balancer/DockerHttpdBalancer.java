package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.ContainerUtils;
import org.jboss.modcluster.test.utils.HttpdImageBuilder;
import org.jboss.modcluster.test.utils.McmpClient;
import org.jboss.modcluster.test.utils.TestTimeouts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apache httpd with mod_proxy_cluster balancer.
 * Managed via MCMP (Mod Cluster Management Protocol) on a dedicated port (8090).
 */
class DockerHttpdBalancer extends DockerBalancer {

    private static final Logger log = LoggerFactory.getLogger(DockerHttpdBalancer.class);

    private McmpClient mcmpClient;

    @Override
    public String getServerHome() {
        return "/usr/local/apache2";
    }

    @Override
    public int getInternalMcmpPort() {
        return MCMP_PORT;
    }

    @Override
    public int getMcmpSslPort() {
        return MCMP_PORT;
    }

    @Override
    public void start() {
        Network freshNetwork = Network.newNetwork();
        ownsNetwork = true;
        start(freshNetwork, "balancer");
    }

    @Override
    public void start(final Network network, final String networkAlias) {
        type = BalancerType.HTTPD;
        this.network = network;
        this.networkAlias = networkAlias;
        startContainer(networkAlias);
    }

    /**
     * Starts the httpd container with mod_proxy_cluster configuration.
     * Copies the mod_proxy_cluster.conf into the container and configures httpd
     * to listen on port 8080 (data) and 8090 (MCMP management).
     * Includes retry logic for transient Podman socket errors (SIGPIPE).
     *
     * @param networkAlias network alias for this container
     */
    private void startContainer(final String networkAlias) {
        final String customImage = System.getProperty("balancer.httpd.image");
        final String imageName;
        if (customImage != null) {
            imageName = customImage;
        } else {
            imageName = HttpdImageBuilder.buildImage();
        }

        ContainerUtils.startWithRetry(() -> {
            GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(imageName))
                    .withNetwork(network)
                    .withNetworkAliases(networkAlias)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withInit(true))
                    .withExposedPorts(skipModProxyCluster
                            ? new Integer[]{HTTP_PORT}
                            : new Integer[]{HTTP_PORT, HTTPS_PORT, MCMP_PORT})
                    .withLogConsumer(outputFrame ->
                            log.info("[HTTPD-{}] {}", networkAlias.toUpperCase(),
                                    outputFrame.getUtf8String().trim()));

            if (skipModProxyCluster) {
                c.withCommand("/bin/sh", "-c",
                                "sed -i 's/^\\(Listen 80\\)$/#\\1/' /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'Listen 8080' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'PidFile /usr/local/apache2/logs/httpd.pid' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'IncludeOptional conf/extra/ajp-*.conf' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'IncludeOptional conf/extra/ssl-*.conf' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'ErrorLog /proc/self/fd/2' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'LogLevel info' >> /usr/local/apache2/conf/httpd.conf && " +
                                "exec /usr/local/apache2/bin/httpd -DFOREGROUND")
                        .waitingFor(Wait.forListeningPort()
                                .withStartupTimeout(TestTimeouts.HTTPD_STARTUP));
            } else {
                c.withCopyFileToContainer(
                                MountableFile.forClasspathResource("httpd/mod_proxy_cluster.conf", 0644),
                                "/usr/local/apache2/conf/extra/mod_proxy_cluster.conf")
                        .withCommand("/bin/sh", "-c",
                                "sed -i 's/^LoadModule proxy_balancer_module/#LoadModule proxy_balancer_module/' " +
                                "/usr/local/apache2/conf/httpd.conf && " +
                                "sed -i 's/^\\(Listen 80\\)$/#\\1/' /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'Listen 8080' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'Include conf/extra/mod_proxy_cluster.conf' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'ErrorLog /proc/self/fd/2' >> /usr/local/apache2/conf/httpd.conf && " +
                                "echo 'LogLevel info' >> /usr/local/apache2/conf/httpd.conf && " +
                                "exec /usr/local/apache2/bin/httpd -DFOREGROUND")
                        .waitingFor(Wait.forHttp("/mod_cluster_manager").forPort(MCMP_PORT)
                                .withStartupTimeout(TestTimeouts.HTTPD_STARTUP));
            }

            container = c;
            container.start();

            if (!skipModProxyCluster) {
                mcmpClient = new McmpClient(container.getHost(), container.getMappedPort(MCMP_PORT));
            }
            log.info("Httpd balancer '{}' started on network: {}", networkAlias, network.getId());
        }, () -> {
            if (container != null) {
                try {
                    container.close();
                } catch (Exception e) {
                    log.debug("Error during cleanup: {}", e.getMessage());
                }
                container = null;
            }
        }, "httpd balancer '" + networkAlias + "'");
    }

    /**
     * Gets the MCMP client, creating it if needed (e.g., after reload).
     *
     * @return the MCMP client for this container
     */
    private McmpClient getMcmpClient() {
        if (mcmpClient == null) {
            mcmpClient = new McmpClient(container.getHost(), container.getMappedPort(MCMP_PORT));
        }
        return mcmpClient;
    }

    @Override
    public Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
        Map<String, org.jboss.dmr.ModelNode> workerInfo = new HashMap<>();

        String infoResponse = getMcmpClient().sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

        for (McmpClient.McmpNodeInfo node : nodes) {
            // mod_proxy_cluster replaces removed nodes' JVMRoute with "REMOVED"
            // in shared memory; skip these stale entries
            if ("REMOVED".equals(node.name)) {
                log.debug("Skipping stale REMOVED node entry (uri={}://{}:{})", node.type, node.host, node.port);
                continue;
            }
            org.jboss.dmr.ModelNode nodeModel = new org.jboss.dmr.ModelNode();
            nodeModel.get("load").set(node.load);
            nodeModel.get("uri").set(node.type + "://" + node.host + ":" + node.port);
            nodeModel.get("load-balancing-group").set(node.lbGroup != null ? node.lbGroup : "");
            workerInfo.put(node.name, nodeModel);
            log.debug("Node '{}' info: load={}, uri={}://{}:{}", node.name, node.load, node.type, node.host, node.port);
        }

        return workerInfo;
    }

    @Override
    public List<String> getBalancerNames() throws Exception {
        String infoResponse = getMcmpClient().sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

        Set<String> balancerNames = new LinkedHashSet<>();
        for (McmpClient.McmpNodeInfo node : nodes) {
            if ("REMOVED".equals(node.name)) {
                continue;
            }
            if (node.balancer != null && !node.balancer.isEmpty()) {
                balancerNames.add(node.balancer);
            }
        }

        List<String> result = new ArrayList<>(balancerNames);
        log.debug("Balancer names: {}", result);
        return result;
    }

    @Override
    public List<String> getRegisteredContexts(final String nodeName) throws Exception {
        String infoResponse = getMcmpClient().sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

        List<String> contexts = new ArrayList<>();
        for (McmpClient.McmpNodeInfo node : nodes) {
            if (nodeName.equals(node.name)) {
                for (McmpClient.McmpContextInfo ctx : node.contexts) {
                    contexts.add(ctx.path);
                }
            }
        }

        return contexts;
    }

    @Override
    public String getContextStatus(final String nodeName, final String contextPath) throws Exception {
        String infoResponse = getMcmpClient().sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

        String normalizedPath = contextPath.startsWith("/") ? contextPath : "/" + contextPath;

        for (McmpClient.McmpNodeInfo node : nodes) {
            if (nodeName.equals(node.name)) {
                for (McmpClient.McmpContextInfo ctx : node.contexts) {
                    if (normalizedPath.equals(ctx.path)) {
                        return ctx.status;
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void disableNode(final String nodeName) throws Exception {
        getMcmpClient().disableNode(nodeName);
    }

    @Override
    public void stopNode(final String nodeName) throws Exception {
        getMcmpClient().stopNode(nodeName);
    }

    @Override
    public void enableNode(final String nodeName) throws Exception {
        getMcmpClient().enableNode(nodeName);
    }

    @Override
    public void removeNode(final String nodeName) throws Exception {
        getMcmpClient().removeNode(nodeName);
    }

    @Override
    public void enableMcmpSsl() {
        getMcmpClient().enableSsl();
    }

    @Override
    public void disableContext(final String nodeName, final String contextPath) throws Exception {
        getMcmpClient().disableApp(nodeName, contextPath, "default-host");
    }

    @Override
    public void stopContext(final String nodeName, final String contextPath) throws Exception {
        getMcmpClient().stopApp(nodeName, contextPath, "default-host");
    }

    @Override
    public void enableContext(final String nodeName, final String contextPath) throws Exception {
        getMcmpClient().enableApp(nodeName, contextPath, "default-host");
    }

    @Override
    public void disableLoadBalancingGroup(final String groupName) throws Exception {
        List<String> nodesInGroup = findNodesInGroup(groupName);
        if (nodesInGroup.isEmpty()) {
            throw new IllegalStateException(
                    "No nodes found in load-balancing group '" + groupName + "' on balancer");
        }
        for (String nodeName : nodesInGroup) {
            getMcmpClient().disableNode(nodeName);
        }
        log.info("Disabled {} nodes in group '{}'", nodesInGroup.size(), groupName);
    }

    @Override
    public void stopLoadBalancingGroup(final String groupName) throws Exception {
        List<String> nodesInGroup = findNodesInGroup(groupName);
        if (nodesInGroup.isEmpty()) {
            throw new IllegalStateException(
                    "No nodes found in load-balancing group '" + groupName + "' on balancer");
        }
        for (String nodeName : nodesInGroup) {
            getMcmpClient().stopNode(nodeName);
        }
        log.info("Stopped {} nodes in group '{}'", nodesInGroup.size(), groupName);
    }

    @Override
    public void enableLoadBalancingGroup(final String groupName) throws Exception {
        List<String> nodesInGroup = findNodesInGroup(groupName);
        if (nodesInGroup.isEmpty()) {
            throw new IllegalStateException(
                    "No nodes found in load-balancing group '" + groupName + "' on balancer");
        }
        for (String nodeName : nodesInGroup) {
            getMcmpClient().enableNode(nodeName);
        }
        log.info("Enabled {} nodes in group '{}'", nodesInGroup.size(), groupName);
    }

    @Override
    public void setMaxRetries(final int maxRetries) throws Exception {
        log.warn("setMaxRetries({}) is a no-op on httpd balancer (httpd does not support runtime max-retries)",
                maxRetries);
    }

    @Override
    public void reload() throws Exception {
        log.info("Reloading httpd balancer (graceful restart)");
        final int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                container.execInContainer("/usr/local/apache2/bin/apachectl", "graceful");
                break;
            } catch (Exception e) {
                if (ContainerUtils.isTransientDockerError(e) && attempt < maxRetries) {
                    log.warn("Reload attempt {}/{} failed with transient error, retrying: {}",
                             attempt, maxRetries, e.getMessage());
                    Thread.sleep(500L * attempt);
                } else {
                    throw e;
                }
            }
        }
        if (mcmpClient != null) {
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> {
                        getMcmpClient().sendInfo();
                        return true;
                    });
        } else {
            await().atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> {
                        HttpURLConnection conn = (HttpURLConnection)
                                new URL("http://" + container.getHost() + ":"
                                        + container.getMappedPort(HTTP_PORT) + "/").openConnection();
                        conn.setConnectTimeout(2000);
                        conn.getResponseCode();
                        return true;
                    });
        }
        log.info("Httpd balancer reloaded successfully");
    }

    /**
     * Finds all node names that belong to a specific load-balancing group.
     *
     * @param groupName the load-balancing group name to search for
     * @return list of node names in the group
     * @throws IOException if MCMP communication fails
     */
    private List<String> findNodesInGroup(final String groupName) throws IOException {
        String infoResponse = getMcmpClient().sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = getMcmpClient().parseInfo(infoResponse);

        List<String> nodesInGroup = new ArrayList<>();
        for (McmpClient.McmpNodeInfo node : nodes) {
            if (groupName.equals(node.lbGroup)) {
                nodesInGroup.add(node.name);
            }
        }
        return nodesInGroup;
    }
}
