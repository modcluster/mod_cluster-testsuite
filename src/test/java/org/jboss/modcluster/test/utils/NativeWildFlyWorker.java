package org.jboss.modcluster.test.utils;

import org.jboss.dmr.ModelNode;
import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Address;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.Values;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native (non-Docker) WildFly worker implementation.
 *
 * <p>Runs WildFly as a local OS process via {@link NativeProcessManager}, started from
 * a per-instance ZIP extraction ({@link NativeServerExtractor}). All workers share the
 * host network namespace and are distinguished by static port offsets
 * ({@link NativePortAllocator}).
 *
 * <p>This implementation mirrors the lifecycle of {@link DockerWildFlyWorker}:
 * <ol>
 *   <li>Extract WildFly ZIP to {@code target/native-servers/{name}/}</li>
 *   <li>Pre-configure JGroups TCP and mod_cluster proxy (via admin-only subprocess)</li>
 *   <li>Start the server with port offset and HA configuration</li>
 *   <li>Deploy demo app</li>
 * </ol>
 *
 * <h3>Configuration caching</h3>
 *
 * <p>The pre-configuration step boots WildFly in {@code --admin-only} mode, applies
 * management operations via Creaper, then shuts down. The result is a modified
 * {@code standalone-ha.xml}. Because this output is deterministic for a given set of
 * inputs (port offsets, balancer type, max-attempts, etc.), we cache it.
 *
 * <p>A SHA-256 hash is computed from all inputs that affect the configuration (see
 * {@link #computeConfigHash()}). The first run for a given hash boots admin-only and
 * saves the result as {@code standalone-ha.xml.cached-<hash>}. Subsequent runs with
 * the same hash skip admin-only entirely and copy the cached file (~14s saved per
 * worker per test).
 *
 * <p>The cache is automatically invalidated when any input changes (different balancer
 * type, different max-attempts, etc.) because the hash will differ. Adding a new
 * configuration parameter only requires adding it to {@link #computeConfigHash()}.
 * The cache is physically cleared by {@code mvn clean} (deletes {@code target/}).
 *
 * <p>File I/O operations ({@link #copyClasspathResource}, {@link #copyLocalFile},
 * {@link #readFile}) operate directly on the local filesystem instead of
 * copying into a container.
 *
 * <p>Thread safety: instances are not thread-safe. A JVM shutdown hook in
 * {@link NativeProcessManager} ensures all processes are killed on exit.
 *
 * @see TestMode
 * @see NativePortAllocator
 * @see NativeServerExtractor
 */
public class NativeWildFlyWorker extends WildFlyWorker {

    private static final Logger log = LoggerFactory.getLogger(NativeWildFlyWorker.class);


    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    private Path serverHome;
    private NativeProcessManager processManager;

    /**
     * Create a new native WildFly worker.
     *
     * @param name     unique worker name (e.g. "worker1", "worker2")
     * @param balancer the balancer this worker is associated with
     */
    NativeWildFlyWorker(String name, Balancer balancer) {
        super(name, balancer);
    }

    @Override
    public void start() {
        try {
            serverHome = NativeServerExtractor.extract(getName());
            resetServerState();

            String configHash = computeConfigHash();
            Path cachedConfig = getCachedConfigPath(configHash);
            Path config = serverHome.resolve("standalone/configuration/standalone-ha.xml");

            if (Files.exists(cachedConfig)) {
                Files.copy(cachedConfig, config, StandardCopyOption.REPLACE_EXISTING);
                log.info("Using cached config for worker '{}' (hash={}, skipped admin-only boot)", getName(), configHash);
            } else {
                preConfigureViaAdminServer();
                Files.copy(config, cachedConfig, StandardCopyOption.REPLACE_EXISTING);
                log.info("Cached config for worker '{}' (hash={})", getName(), configHash);
            }

            List<String> command = buildStartCommand();
            Map<String, String> env = buildEnvironment();

            processManager = new NativeProcessManager(getName(), command, serverHome, env);
            processManager.start();
            processManager.waitForStartup(STARTUP_LOG_PATTERN, STARTUP_TIMEOUT);

            log.info("WildFly worker '{}' started natively at {}", getName(), serverHome);

            deployment().deployDemoApp();
        } catch (Exception e) {
            if (processManager != null && processManager.isRunning()) {
                log.warn("Killing WildFly process for '{}' after startup failure", getName());
                processManager.kill();
                processManager = null;
                clearCachedManagers();
            }
            throw new RuntimeException("Failed to start native WildFly worker '" + getName() + "'", e);
        }
    }

    /**
     * Reset mutable server state so each test run starts with a clean configuration.
     * Restores the original {@code standalone-ha.xml} from the backup created during
     * extraction, and removes runtime data directories that may hold stale state.
     */
    private void resetServerState() throws IOException {
        Path configBackup = serverHome.resolve("standalone/configuration/standalone-ha.xml.original");
        Path config = serverHome.resolve("standalone/configuration/standalone-ha.xml");
        if (Files.exists(configBackup)) {
            Files.copy(configBackup, config, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Restored clean standalone-ha.xml for '{}'", getName());
        }

        deleteDirectoryRecursively(serverHome.resolve("standalone/data"));
        deleteDirectoryRecursively(serverHome.resolve("standalone/tmp"));
        deleteDirectoryRecursively(serverHome.resolve("standalone/configuration/standalone_xml_history"));
    }

    /**
     * Compute a hash of all inputs that determine the pre-configured {@code standalone-ha.xml}.
     *
     * <p>The hash covers every value read by {@link #configureJGroupsTcp} and
     * {@link #configureModClusterProxy}. If a new configuration parameter is added
     * to either method, it <b>must</b> be added here as well — otherwise stale cached
     * configs will be reused silently.
     *
     * @return first 8 hex characters of the SHA-256 digest
     */
    private String computeConfigHash() {
        String input = String.join("|",
            NativePortAllocator.tcppingInitialHosts(),
            String.valueOf(getBalancer().getInternalMcmpPort()),
            String.valueOf(modCluster().getDesiredMaxAttempts()),
            getBalancer().getType().getName()
        );
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private Path getCachedConfigPath(String hash) {
        return serverHome.resolve("standalone/configuration/standalone-ha.xml.cached-" + hash);
    }

    /**
     * Pre-configure JGroups TCP and mod_cluster proxy via a WildFly admin-only subprocess.
     * Starts the server in {@code --admin-only} mode as a separate process, connects
     * via Creaper {@link Operations} over the management port, applies configuration,
     * then stops the subprocess. The configuration is persisted to
     * {@code standalone-ha.xml} so the real server boots fully configured.
     */
    private void preConfigureViaAdminServer() throws Exception {
        log.info("Pre-configuring worker '{}' via admin-only server", getName());

        List<String> command = buildStartCommand();
        command.add("--admin-only");

        NativeProcessManager adminProcess = new NativeProcessManager(
            getName() + "-admin", command, serverHome, buildEnvironment());
        adminProcess.start();
        adminProcess.waitForStartup(STARTUP_LOG_PATTERN, STARTUP_TIMEOUT);

        try {
            OnlineManagementClient client = ManagementClientFactory.create(
                "localhost", NativePortAllocator.managementPort(getName()));
            try {
                Operations ops = new Operations(client);
                configureJGroupsTcp(ops);
                configureModClusterProxy(ops);
            } finally {
                client.close();
            }
        } finally {
            adminProcess.stop();
        }

        log.info("Admin-only pre-configuration completed for worker '{}'", getName());
    }

    private void configureJGroupsTcp(Operations ops) throws Exception {
        String initialHosts = NativePortAllocator.tcppingInitialHosts();

        Address channelAddr = Address.subsystem("jgroups").and("channel", "ee");
        ops.writeAttribute(channelAddr, "stack", "tcp").assertSuccess();
        ops.writeAttribute(channelAddr, "statistics-enabled", true).assertSuccess();

        Address tcppingAddr = Address.subsystem("jgroups").and("stack", "tcp").and("protocol", "TCPPING");
        if (!ops.exists(tcppingAddr)) {
            ModelNode properties = new ModelNode();
            properties.get("initial_hosts").set(initialHosts);
            properties.get("port_range").set("0");
            ops.add(tcppingAddr, Values.of("add-index", 0).and("properties", properties)).assertSuccess();
        }

        ops.removeIfExists(Address.subsystem("jgroups").and("stack", "tcp").and("socket-discovery-protocol", "MPING"));
        ops.removeIfExists(Address.subsystem("jgroups").and("stack", "tcp").and("protocol", "MPING"));

        Address tcpTransport = Address.subsystem("jgroups").and("stack", "tcp").and("transport", "TCP");
        ops.invoke("map-put", tcpTransport,
            Values.of("name", "properties").and("key", "external_addr").and("value", "localhost")).assertSuccess();
        ops.invoke("map-put", tcpTransport,
            Values.of("name", "properties").and("key", "sock_conn_timeout").and("value", "10000")).assertSuccess();

        Address fdSock2Addr = Address.subsystem("jgroups").and("stack", "tcp").and("protocol", "FD_SOCK2");
        if (ops.exists(fdSock2Addr)) {
            ops.invoke("map-put", fdSock2Addr,
                Values.of("name", "properties").and("key", "external_addr").and("value", "localhost")).assertSuccess();
        } else {
            ModelNode fdProps = new ModelNode();
            fdProps.get("external_addr").set("localhost");
            ops.add(fdSock2Addr, Values.of("add-index", 2).and("properties", fdProps)).assertSuccess();
        }

        Address fdAll3Addr = Address.subsystem("jgroups").and("stack", "tcp").and("protocol", "FD_ALL3");
        if (ops.exists(fdAll3Addr)) {
            ops.invoke("map-put", fdAll3Addr,
                Values.of("name", "properties").and("key", "timeout").and("value", "5000")).assertSuccess();
            ops.invoke("map-put", fdAll3Addr,
                Values.of("name", "properties").and("key", "interval").and("value", "1500")).assertSuccess();
        }

        ops.invoke("map-put",
            Address.subsystem("jgroups").and("stack", "tcp").and("protocol", "pbcast.GMS"),
            Values.of("name", "properties").and("key", "join_timeout").and("value", "10000")).assertSuccess();
    }

    private void configureModClusterProxy(Operations ops) throws Exception {
        int mcmpPort = getBalancer().getInternalMcmpPort();
        int maxAttempts = modCluster().getDesiredMaxAttempts();
        boolean isHttpd = getBalancer().getType() == BalancerType.HTTPD;

        Address socketBindingAddr = Address.of("socket-binding-group", "standard-sockets")
            .and("remote-destination-outbound-socket-binding", "modcluster-balancer");
        ops.add(socketBindingAddr, Values.of("host", "localhost").and("port", mcmpPort)).assertSuccess();

        Address proxyAddr = Address.subsystem("modcluster").and("proxy", "default");
        ModelNode proxyList = new ModelNode();
        proxyList.add("modcluster-balancer");
        ops.writeAttribute(proxyAddr, "proxies", proxyList).assertSuccess();
        ops.writeAttribute(proxyAddr, "listener", "default").assertSuccess();

        if (maxAttempts >= 0) {
            ops.writeAttribute(proxyAddr, "max-attempts", maxAttempts).assertSuccess();
        }

        if (isHttpd) {
            ops.writeAttribute(proxyAddr, "ping", 3).assertSuccess();
        }
    }

    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        log.warn("Failed to delete {}: {}", p, e.getMessage());
                    }
                });
        }
    }

    /**
     * Build the WildFly startup command with port offset and HA configuration.
     *
     * <p>Uses {@code standalone.sh} on Unix or {@code standalone.bat} on Windows.
     * Binds all interfaces to {@code 0.0.0.0} so the server is reachable on localhost.
     *
     * @return the command and arguments as a list
     */
    private List<String> buildStartCommand() {
        String script = TestMode.isWindows() ? "standalone.bat" : "standalone.sh";
        Path scriptPath = serverHome.resolve("bin").resolve(script);

        List<String> cmd = new ArrayList<>();
        cmd.add(scriptPath.toAbsolutePath().toString());
        cmd.add("-b");
        cmd.add("0.0.0.0");
        cmd.add("-bmanagement");
        cmd.add("0.0.0.0");
        cmd.add("-bprivate");
        cmd.add("0.0.0.0");
        cmd.add("-Djboss.node.name=" + getName());
        cmd.add("-Djboss.server.default.config=standalone-ha.xml");
        cmd.add("-Djboss.socket.binding.port-offset=" + NativePortAllocator.offset(getName()));
        cmd.add("-Djboss.modcluster.multicast.address=224.0.1.105");
        cmd.add("-Djboss.modcluster.multicast.port=23364");

        return cmd;
    }

    /**
     * Build the environment variables for the WildFly process.
     *
     * @return environment variable map (may be empty)
     */
    private Map<String, String> buildEnvironment() {
        Map<String, String> env = new HashMap<>();
        if (javaOpts != null) {
            env.put("JAVA_OPTS", javaOpts);
        }
        return env;
    }

    @Override
    public void stop() {
        closeManagementClient();
        if (processManager != null) {
            processManager.stop();
            processManager = null;
            clearCachedManagers();
        }
        log.info("WildFly worker '{}' stopped", getName());
    }

    @Override
    public void restartServer() throws Exception {
        log.info("Restarting worker '{}' via process stop+start", getName());
        stop();

        List<String> command = buildStartCommand();
        Map<String, String> env = buildEnvironment();

        processManager = new NativeProcessManager(getName(), command, serverHome, env);
        processManager.start();
        processManager.waitForStartup(STARTUP_LOG_PATTERN, STARTUP_TIMEOUT);

        deployment().deployDemoApp();
        log.info("Worker '{}' restarted successfully", getName());
    }

    @Override
    public void kill() throws Exception {
        closeManagementClient();
        if (processManager != null) {
            processManager.kill();
            processManager = null;
            clearCachedManagers();
        }
        log.info("WildFly worker '{}' killed", getName());
    }

    @Override
    public boolean isRunning() {
        return processManager != null && processManager.isRunning();
    }

    @Override
    public String getServerHome() {
        return serverHome != null ? serverHome.toAbsolutePath().toString() : null;
    }

    @Override
    public String getTempDirectory() {
        return System.getProperty("java.io.tmpdir");
    }

    @Override
    public String getHttpUrl() {
        return "http://localhost:" + NativePortAllocator.httpPort(getName());
    }

    @Override
    public String getHttpsUrl() {
        return "https://localhost:" + NativePortAllocator.httpsPort(getName());
    }

    @Override
    public String getManagementUrl() {
        return "http://localhost:" + NativePortAllocator.managementPort(getName());
    }

    @Override
    public String getInternalHttpUrl() {
        return "http://localhost:" + NativePortAllocator.httpPort(getName());
    }

    @Override
    public String getProxyHost() {
        return "localhost";
    }

    @Override
    protected String getManagementHost() {
        return "localhost";
    }

    @Override
    protected int getManagementPort() {
        return NativePortAllocator.managementPort(getName());
    }

    @Override
    public CommandResult execCommand(String... command) throws Exception {
        return NativeProcessManager.execCommand(serverHome, command);
    }

    @Override
    public void copyClasspathResource(String classpathResource, String destPath) {
        try {
            Path dest = Path.of(destPath);
            if (!dest.isAbsolute()) {
                dest = serverHome.resolve(destPath);
            }
            Files.createDirectories(dest.getParent());

            URL resource = Thread.currentThread().getContextClassLoader().getResource(classpathResource);
            if (resource == null) {
                throw new RuntimeException("Classpath resource not found: " + classpathResource);
            }

            try (InputStream is = resource.openStream()) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Copied classpath resource '{}' to '{}'", classpathResource, dest);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy classpath resource '" + classpathResource
                    + "' to '" + destPath + "'", e);
        }
    }

    @Override
    public void copyLocalFile(Path hostPath, String destPath) throws Exception {
        Path dest = Path.of(destPath);
        if (!dest.isAbsolute()) {
            dest = serverHome.resolve(destPath);
        }
        Files.createDirectories(dest.getParent());
        Files.copy(hostPath, dest, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Copied local file '{}' to '{}'", hostPath, dest);
    }

    @Override
    public String readFile(String path) throws Exception {
        Path filePath = Path.of(path);
        if (!filePath.isAbsolute()) {
            filePath = serverHome.resolve(path);
        }
        return Files.readString(filePath);
    }

    @Override
    public String getServerLog() throws Exception {
        Path logPath = serverHome.resolve("standalone/log/server.log");
        if (Files.exists(logPath)) {
            return Files.readString(logPath);
        }
        return processManager != null ? processManager.readOutputLog() : "";
    }

    @Override
    public String getServerLog(int lines) throws Exception {
        Path logPath = serverHome.resolve("standalone/log/server.log");
        if (Files.exists(logPath)) {
            List<String> allLines = Files.readAllLines(logPath);
            int start = Math.max(0, allLines.size() - lines);
            return String.join("\n", allLines.subList(start, allLines.size()));
        }
        return processManager != null ? processManager.readOutputLog() : "";
    }

    @Override
    public String grepServerLog(String pattern) throws Exception {
        Path logPath = serverHome.resolve("standalone/log/server.log");
        if (!Files.exists(logPath)) {
            return "Log file not found";
        }
        StringBuilder matches = new StringBuilder();
        for (String line : Files.readAllLines(logPath)) {
            if (line.toLowerCase().contains(pattern.toLowerCase())) {
                matches.append(line).append("\n");
            }
        }
        return matches.length() > 0 ? matches.toString() : "No matches found";
    }

}
