package org.jboss.modcluster.test.utils;

import org.jboss.modcluster.test.utils.balancer.Balancer;
import org.jboss.dmr.ModelNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;
import org.wildfly.extras.creaper.core.online.operations.Operations;
import org.wildfly.extras.creaper.core.online.operations.admin.Administration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstract wrapper for a WildFly/EAP worker with mod_cluster subsystem.
 * Platform-independent API — Docker and native implementations provide
 * concrete process management, file I/O, and networking.
 */
public abstract class WildFlyWorker {

    private static final Logger log = LoggerFactory.getLogger(WildFlyWorker.class);

    /** WildFly log message code emitted when the server has started successfully. */
    public static final String STARTUP_LOG_PATTERN = "WFLYSRV0025";

    private final String name;
    private final Balancer balancer;
    protected String javaOpts;
    protected OnlineManagementClient managementClient;
    private WildFlyDeploymentManager deploymentManager;
    private WildFlyModClusterManager modClusterManager;
    private WildFlyUndertowManager undertowManager;
    private WildFlyLoadMetricsManager loadMetricsManager;
    private WildFlyJGroupsManager jgroupsManager;

    protected WildFlyWorker(String name, Balancer balancer) {
        this.name = name;
        this.balancer = balancer;
    }

    /**
     * Create a WildFlyWorker for the current test mode.
     *
     * <p>Dispatches based on the {@code test.mode} system property:
     * <ul>
     *   <li>{@link TestMode#DOCKER} (default): returns a {@link DockerWildFlyWorker}</li>
     *   <li>{@link TestMode#NATIVE}: returns a {@link NativeWildFlyWorker}</li>
     * </ul>
     *
     * @param name     unique worker name (e.g. "worker1")
     * @param balancer the balancer this worker is associated with
     * @return a new worker instance for the current test mode
     */
    public static WildFlyWorker create(String name, Balancer balancer) {
        if (TestMode.current().isNative()) {
            return new NativeWildFlyWorker(name, balancer);
        }
        return new DockerWildFlyWorker(name, balancer);
    }

    // ---- Abstract methods (platform-specific) ----

    /** Start the WildFly server process and wait until management is available. */
    public abstract void start();

    /** Stop the WildFly server process and release all resources. */
    public abstract void stop();

    /**
     * Hard kill the worker (simulates crash/SIGKILL).
     */
    public abstract void kill() throws Exception;

    /** Whether the WildFly server process is currently running. */
    public abstract boolean isRunning();

    /** External HTTP URL for test client requests (e.g. {@code http://localhost:8180}). */
    public abstract String getHttpUrl();

    /** External HTTPS URL for test client requests (e.g. {@code https://localhost:8543}). */
    public abstract String getHttpsUrl();

    /** External management URL for Creaper connections (e.g. {@code http://localhost:10090}). */
    public abstract String getManagementUrl();

    /**
     * Get the internal URL reachable by other workers/balancer on the same network.
     * Docker: uses container hostname. Native: uses localhost with port offset.
     */
    public abstract String getInternalHttpUrl();

    /**
     * Get the hostname the balancer is reachable at from this worker.
     * Docker: returns the network alias (e.g. "balancer"). Native: returns "localhost".
     */
    public abstract String getProxyHost();

    /**
     * Get the management interface host for Creaper connections.
     */
    protected abstract String getManagementHost();

    /**
     * Get the management interface port for Creaper connections.
     */
    protected abstract int getManagementPort();

    /**
     * Get the server home directory path.
     *
     * <p>Docker: returns {@code "/opt/wildfly"} (fixed path inside the container image).
     * Native: returns the path where the WildFly distribution was extracted
     * (e.g. {@code "target/native-servers/worker1/wildfly-39.0.1.Final"}).
     *
     * <p>Used by SSL configurators, load metric module installers, and EJB tests
     * to locate server binaries and configuration files without hardcoding paths.
     *
     * @return the absolute path to the WildFly server home directory
     */
    public abstract String getServerHome();

    /**
     * Get the system temporary directory path appropriate for this worker's environment.
     *
     * <p>Docker: returns {@code "/tmp"} (known to exist in Linux containers).
     * Native: returns {@code java.io.tmpdir} (OS-appropriate — {@code /tmp} on Linux,
     * {@code C:\Users\...\AppData\Local\Temp} on Windows).
     *
     * @return absolute path to the system temporary directory
     */
    public abstract String getTempDirectory();

    /**
     * Execute a command inside the worker environment.
     */
    public abstract CommandResult execCommand(String... command) throws Exception;

    /**
     * Copy a classpath resource to the worker's filesystem.
     */
    public abstract void copyClasspathResource(String classpathResource, String destPath);

    /**
     * Copy a local file to the worker's filesystem.
     */
    public abstract void copyLocalFile(Path hostPath, String destPath) throws Exception;

    /**
     * Read a file from the worker's filesystem.
     */
    public abstract String readFile(String path) throws Exception;

    /**
     * Get the server log content.
     */
    public abstract String getServerLog() throws Exception;

    /**
     * Get the last N lines from the server log.
     */
    public abstract String getServerLog(int lines) throws Exception;

    /**
     * Grep the server log for specific patterns.
     */
    public abstract String grepServerLog(String pattern) throws Exception;

    // ---- Concrete methods (shared across all implementations) ----

    /**
     * Override JVM options for this worker. Must be called before {@link #start()}.
     * Useful for tests that need more heap (e.g., heap load metric tests).
     */
    public WildFlyWorker withJavaOpts(String javaOpts) {
        this.javaOpts = javaOpts;
        return this;
    }

    /**
     * Pre-configure max-attempts before worker startup.
     * The value is applied during proxy configuration, before the worker
     * joins the cluster — avoiding a disruptive reload in a running cluster.
     *
     * @param maxAttempts the maximum number of retry attempts, or -1 to keep defaults
     */
    public WildFlyWorker withMaxAttempts(int maxAttempts) {
        modCluster().setDesiredMaxAttempts(maxAttempts);
        return this;
    }

    /** Get the unique name of this worker (e.g. "worker1"). */
    public String getName() {
        return name;
    }

    /**
     * Get the balancer that this worker is associated with.
     */
    public Balancer getBalancer() {
        return balancer;
    }

    /**
     * Graceful shutdown: management API shutdown + stop.
     */
    public void shutdown() {
        if (managementClient != null) {
            try {
                log.info("Initiating management API shutdown for worker '{}'", name);
                new Administration(managementClient).shutdown();
                Thread.sleep(2000); // Let JGroups send LEAVE
            } catch (IOException e) {
                log.debug("Management connection closed during shutdown (expected): {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Management API shutdown failed for '{}': {}", name, e.getMessage());
            }
        }
        stop();
    }

    /**
     * Get Creaper ManagementClient for this WildFly instance.
     * Creates client on first call, reuses it afterwards.
     */
    public OnlineManagementClient getManagementClient() throws IOException {
        if (managementClient == null) {
            managementClient = ManagementClientFactory.create(
                    getManagementHost(), getManagementPort());
            log.debug("Created management client for worker '{}'", name);
        }
        return managementClient;
    }

    /**
     * Get Creaper Operations helper for this WildFly instance.
     */
    public Operations getOperations() throws IOException {
        return new Operations(getManagementClient());
    }

    /**
     * Get Creaper Administration helper for this WildFly instance.
     */
    public Administration getAdministration() throws IOException {
        return new Administration(getManagementClient());
    }

    /** Get the deployment manager for deploying/undeploying applications. */
    public WildFlyDeploymentManager deployment() {
        if (deploymentManager == null) {
            deploymentManager = new WildFlyDeploymentManager(this);
        }
        return deploymentManager;
    }

    /** Get the mod_cluster subsystem manager for proxy configuration. */
    public WildFlyModClusterManager modCluster() {
        if (modClusterManager == null) {
            modClusterManager = new WildFlyModClusterManager(this);
        }
        return modClusterManager;
    }

    /** Get the Undertow subsystem manager for listener and host configuration. */
    public WildFlyUndertowManager undertow() {
        if (undertowManager == null) {
            undertowManager = new WildFlyUndertowManager(this);
        }
        return undertowManager;
    }

    /** Get the load metrics manager for configuring custom load providers. */
    public WildFlyLoadMetricsManager loadMetrics() {
        if (loadMetricsManager == null) {
            loadMetricsManager = new WildFlyLoadMetricsManager(this);
        }
        return loadMetricsManager;
    }

    /** Get the JGroups manager for cluster view and protocol configuration. */
    public WildFlyJGroupsManager jgroups() {
        if (jgroupsManager == null) {
            jgroupsManager = new WildFlyJGroupsManager(this);
        }
        return jgroupsManager;
    }

    /**
     * Execute a CLI command on this WildFly instance using Creaper.
     *
     * @deprecated Use getManagementClient() and Creaper operations instead
     */
    @Deprecated
    public String executeCli(String command) throws Exception {
        OnlineManagementClient client = getManagementClient();
        ModelNode result = client.execute(command);
        return result.toJSONString(false);
    }

    /**
     * Execute a CLI command using shell (fallback for complex commands).
     */
    public String executeCliViaShell(String command) throws Exception {
        CommandResult result = execCommand(
                "sh", "-c",
                "jboss-cli.sh --connect --controller=localhost:9990 --command='" + command + "'");

        if (result.getExitCode() != 0) {
            throw new RuntimeException("CLI command failed: " + result.getStderr());
        }

        return result.getStdout();
    }

    /**
     * Reload the server configuration and wait for management to be ready.
     * Does not reconfigure static proxy or redeploy applications.
     */
    public void reloadServer() throws Exception {
        log.info("Reloading worker '{}'", name);
        try {
            getAdministration().reload();
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.TimeoutException
                    || e.getCause() instanceof java.util.concurrent.TimeoutException
                    || (e.getMessage() != null && e.getMessage().contains("Waiting for server timed out"))) {
                log.warn("Reload timed out for '{}', waiting with fresh connection (bootTimeout=120s)", name);
                closeManagementClient();
                getAdministration().waitUntilRunning();
            } else {
                throw e;
            }
        }
        log.info("Worker '{}' reloaded successfully", name);
    }

    /**
     * Restart the server (full JVM restart, heavier than reload).
     */
    public void restartServer() throws Exception {
        log.info("Restarting worker '{}'", name);
        getAdministration().restart();
        managementClient = null;
        log.info("Worker '{}' restarted successfully", name);
    }

    /**
     * Reload the server configuration.
     * All management model state (deployments, proxy config) persists across reloads.
     */
    public void reload() throws Exception {
        reloadServer();
    }

    protected void closeManagementClient() {
        if (managementClient != null) {
            try {
                managementClient.close();
            } catch (IOException e) {
                log.warn("Error closing management client for worker '{}'", name, e);
            }
            managementClient = null;
        }
    }

    protected void clearCachedManagers() {
        deploymentManager = null;
        modClusterManager = null;
        undertowManager = null;
        loadMetricsManager = null;
        jgroupsManager = null;
    }
}
