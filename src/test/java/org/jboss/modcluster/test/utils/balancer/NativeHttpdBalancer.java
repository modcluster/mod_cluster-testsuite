package org.jboss.modcluster.test.utils.balancer;

import org.jboss.modcluster.test.base.BalancerType;
import org.jboss.modcluster.test.ssl.SSLConfigurator;
import org.jboss.modcluster.test.utils.CommandResult;
import org.jboss.modcluster.test.utils.McmpClient;
import org.jboss.modcluster.test.utils.NativePortAllocator;
import org.jboss.modcluster.test.utils.NativeProcessManager;
import org.jboss.modcluster.test.utils.TestMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.awaitility.Awaitility.await;

/**
 * Native (non-Docker) Apache httpd with mod_proxy_cluster balancer.
 *
 * <p>Runs httpd as a local OS process, using a JBCS (JBoss Core Services) httpd
 * distribution extracted from a ZIP file in {@code distributions/}. MCMP operations
 * use the platform-agnostic {@link McmpClient}.
 *
 * <p>Startup flow:
 * <ol>
 *   <li>Detect and extract JBCS httpd ZIP from {@code -Dhttpd.zip.path} or {@code distributions/}</li>
 *   <li>Locate httpd binary ({@code sbin/httpd} on Linux, {@code bin/httpd.exe} on Windows)</li>
 *   <li>Patch {@code httpd.conf}: set {@code Listen 8080}, disable {@code mod_proxy_balancer},
 *       include {@code mod_proxy_cluster.conf}</li>
 *   <li>Copy {@code mod_proxy_cluster.conf} from classpath resources</li>
 *   <li>Start httpd in foreground mode via {@link NativeProcessManager}</li>
 *   <li>Poll MCMP endpoint until responsive</li>
 * </ol>
 *
 * @see McmpClient
 * @see NativePortAllocator
 */
class NativeHttpdBalancer extends Balancer {

    private static final Logger log = LoggerFactory.getLogger(NativeHttpdBalancer.class);

    /** httpd ports — no offset (single httpd instance). */
    private static final int HTTP_PORT = 8080;
    private static final int HTTPS_PORT = 8443;
    private static final int MCMP_PORT = NativePortAllocator.HTTPD_MCMP_PORT;

    private static final Path WORK_DIR = Path.of("target", "native-servers", "httpd");

    private Path httpdHome;
    private Path httpdBinary;
    private Path confFile;
    private Path modulesPath;
    private NativeProcessManager processManager;
    private McmpClient mcmpClient;

    @Override
    public void start() {
        type = BalancerType.HTTPD;

        try {
            resolveHttpdInstallation();
            setupConfiguration();

            List<String> command = List.of(
                    httpdBinary.toAbsolutePath().toString(),
                    "-d", serverRoot().toAbsolutePath().toString(),
                    "-f", confFile.toAbsolutePath().toString(),
                    "-DFOREGROUND");

            processManager = new NativeProcessManager("httpd-balancer", command, serverRoot(), null);
            processManager.start();

            mcmpClient = new McmpClient("localhost", MCMP_PORT);

            // Poll until MCMP endpoint is responsive
            try {
                await().atMost(Duration.ofSeconds(30))
                        .pollInterval(Duration.ofSeconds(1))
                        .ignoreExceptions()
                        .until(() -> {
                            mcmpClient.sendInfo();
                            return true;
                        });
            } catch (Exception timeout) {
                logHttpdDiagnostics();
                throw timeout;
            }

            log.info("Native httpd balancer started at {}", httpdHome);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start native httpd balancer", e);
        }
    }

    /**
     * Resolve the httpd installation to use.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code -Dhttpd.home} — use an existing httpd installation directly</li>
     *   <li>{@code -Dhttpd.zip.path} or auto-discovered ZIP in {@code distributions/}
     *       — extract and use a JBCS distribution</li>
     * </ol>
     */
    private void resolveHttpdInstallation() throws IOException {
        String homeProp = System.getProperty("httpd.home");
        if (homeProp != null && !homeProp.isBlank()) {
            httpdHome = Path.of(homeProp);
            if (!Files.isDirectory(httpdHome)) {
                throw new RuntimeException("httpd.home does not exist: " + homeProp);
            }
            httpdBinary = findHttpdBinary(httpdHome);
            log.info("Using system httpd: {}", httpdBinary);
        } else {
            Path jbcsZip = findJbcsZip();
            Path extractionRoot = extractJbcsZip(jbcsZip);
            extractConnectorsIfAvailable(jbcsZip);
            httpdBinary = findHttpdBinary(extractionRoot);
            httpdHome = httpdBinary.getParent().getParent();
            runPostinstallIfNeeded(httpdHome);
            log.info("Using extracted httpd: {}", httpdHome);
        }

        String modulesProp = System.getProperty("httpd.modules.path");
        if (modulesProp != null && !modulesProp.isBlank()) {
            modulesPath = Path.of(modulesProp).toAbsolutePath();
            if (!Files.isDirectory(modulesPath)) {
                throw new RuntimeException("httpd.modules.path does not exist: " + modulesProp);
            }
            log.info("Using external modules directory: {}", modulesPath);
        }
    }

    /**
     * Set up the httpd configuration in a working directory.
     *
     * <p>For system httpd ({@code -Dhttpd.home}), we create a fresh working directory
     * under {@code target/native-servers/httpd/work/} since we cannot write to the
     * system config directories. For extracted ZIPs, we patch the config in-place.
     */
    private void setupConfiguration() throws IOException {
        boolean isSystemHttpd = System.getProperty("httpd.home") != null;

        if (isSystemHttpd) {
            setupSystemHttpdWorkDir();
        } else {
            confFile = findHttpdConf(httpdHome);
            if (confFile == null) {
                throw new RuntimeException("httpd.conf not found under " + httpdHome
                        + " (even after postinstall). Check the JBCS distribution layout.");
            }
            patchHttpdConf();
            removeConflictingConfigs();
        }

        Files.createDirectories(confFile.getParent().resolve("extra"));
    }

    /**
     * Create a working directory with a minimal httpd.conf for system httpd.
     * This avoids modifying the system configuration in /etc/httpd or /etc/apache2.
     */
    private void setupSystemHttpdWorkDir() throws IOException {
        Path workDir = WORK_DIR.resolve("work");

        // Clean previous work dir to remove stale SSL configs from prior test classes
        if (Files.isDirectory(workDir)) {
            try (Stream<Path> walk = Files.walk(workDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }

        Path confDir = workDir.resolve("conf");
        Path confDDir = workDir.resolve("conf.d");
        Path logsDir = workDir.resolve("logs");
        Path extraDir = confDir.resolve("extra");

        Files.createDirectories(confDir);
        Files.createDirectories(confDDir);
        Files.createDirectories(logsDir);
        Files.createDirectories(extraDir);

        Path systemModules = resolveSystemModulesDir();

        // Create modules/ dir with symlinks to system modules and mod_proxy_cluster modules,
        // so relative LoadModule paths in conf templates work.
        Path modulesLink = workDir.resolve("modules");
        Files.createDirectories(modulesLink);
        try (Stream<Path> stream = Files.list(systemModules)) {
            for (Path so : stream.filter(p -> p.toString().endsWith(".so")).toList()) {
                Files.createSymbolicLink(modulesLink.resolve(so.getFileName()), so.toAbsolutePath());
            }
        }
        if (modulesPath != null) {
            try (Stream<Path> stream = Files.list(modulesPath)) {
                for (Path so : stream.filter(p -> p.toString().endsWith(".so")).toList()) {
                    Path link = modulesLink.resolve(so.getFileName());
                    Files.deleteIfExists(link); // override system module with mod_proxy_cluster version
                    Files.createSymbolicLink(link, so.toAbsolutePath());
                }
            }
        }

        StringBuilder conf = new StringBuilder();
        conf.append("ServerRoot \"").append(workDir.toAbsolutePath()).append("\"\n");
        conf.append("PidFile \"").append(logsDir.toAbsolutePath().resolve("httpd.pid")).append("\"\n");
        conf.append("ErrorLog \"").append(logsDir.toAbsolutePath().resolve("error_log")).append("\"\n");
        conf.append("LogLevel info\n\n");

        // Load standard modules from system modules dir (IfModule guards handle built-in modules)
        for (String module : List.of(
                "mpm_event_module:mod_mpm_event.so",
                "authz_core_module:mod_authz_core.so",
                "unixd_module:mod_unixd.so",
                "log_config_module:mod_log_config.so",
                "proxy_module:mod_proxy.so",
                "proxy_http_module:mod_proxy_http.so",
                "proxy_ajp_module:mod_proxy_ajp.so",
                "proxy_wstunnel_module:mod_proxy_wstunnel.so",
                "slotmem_shm_module:mod_slotmem_shm.so",
                "watchdog_module:mod_watchdog.so",
                "ssl_module:mod_ssl.so",
                "socache_shmcb_module:mod_socache_shmcb.so")) {
            String[] parts = module.split(":");
            Path soFile = systemModules.toAbsolutePath().resolve(parts[1]);
            conf.append("<IfModule !").append(parts[0]).append(">\n");
            conf.append("    LoadModule ").append(parts[0]).append(" ").append(soFile).append("\n");
            conf.append("</IfModule>\n");
        }

        // Load mod_proxy_cluster modules from the modules path (external or system)
        conf.append("\n# mod_proxy_cluster modules\n");
        Path mpcModules = modulesPath != null ? modulesPath : systemModules;
        for (String module : List.of(
                "manager_module:mod_manager.so",
                "proxy_cluster_module:mod_proxy_cluster.so",
                "advertise_module:mod_advertise.so")) {
            String[] parts = module.split(":");
            Path soFile = mpcModules.resolve(parts[1]);
            if (Files.isRegularFile(soFile)) {
                conf.append("<IfModule !").append(parts[0]).append(">\n");
                conf.append("    LoadModule ").append(parts[0]).append(" ")
                        .append(soFile.toAbsolutePath()).append("\n");
                conf.append("</IfModule>\n");
            }
        }
        // Optional modules
        for (String module : List.of(
                "lbmethod_cluster_module:mod_lbmethod_cluster.so",
                "cluster_slotmem_module:mod_cluster_slotmem.so")) {
            String[] parts = module.split(":");
            Path soFile = mpcModules.resolve(parts[1]);
            if (Files.isRegularFile(soFile)) {
                conf.append("<IfModule !").append(parts[0]).append(">\n");
                conf.append("    LoadModule ").append(parts[0]).append(" ")
                        .append(soFile.toAbsolutePath()).append("\n");
                conf.append("</IfModule>\n");
            }
        }

        conf.append("\n#Listen 80\n");
        conf.append("Listen 8080\n\n");

        // MCMP, VirtualHost, and SSL includes come from conf.d/mod_proxy_cluster.conf
        conf.append("IncludeOptional conf.d/*.conf\n");

        confFile = confDir.resolve("httpd.conf");
        Files.writeString(confFile, conf.toString());
        log.info("Generated httpd.conf at {}", confFile);

        // Copy mod_proxy_cluster.conf template to conf.d/
        copyModProxyClusterConf();
    }

    /**
     * Find the system httpd modules directory.
     * Checks common locations for Fedora/RHEL and Debian/Ubuntu.
     */
    private Path resolveSystemModulesDir() {
        for (String candidate : List.of(
                "lib64/httpd/modules",
                "lib/apache2/modules",
                "modules")) {
            Path dir = httpdHome.resolve(candidate);
            if (Files.isDirectory(dir)) return dir;
        }
        throw new RuntimeException("Cannot find httpd modules directory under " + httpdHome
                + ". Set -Dhttpd.modules.path to specify the location.");
    }

    @Override
    public void stop() {
        if (processManager != null) {
            processManager.stop();
            processManager = null;
        }
        log.info("httpd balancer stopped");
    }

    @Override
    public void startOnSameNetworkAs(Balancer other, String alias) {
        start();
    }

    // ---- Networking methods ----

    @Override
    public String getHttpUrl() {
        return "http://localhost:" + HTTP_PORT;
    }

    @Override
    public String getHttpsUrl() {
        return "https://localhost:" + HTTPS_PORT;
    }

    @Override
    public String getMcmpUrl() {
        return "http://localhost:" + MCMP_PORT;
    }

    @Override
    public String getInternalHttpUrl() {
        return "http://localhost:" + HTTP_PORT;
    }

    @Override
    public String getProxyHost() {
        return "localhost";
    }

    @Override
    public String getManagementHost() {
        return "localhost";
    }

    @Override
    public int getManagementPort() {
        return MCMP_PORT;
    }

    @Override
    public String getServerHome() {
        return serverRoot().toAbsolutePath().toString();
    }

    @Override
    public String getConfDir() {
        return requireConfFile().getParent().toAbsolutePath().toString();
    }

    @Override
    public String getModProxyClusterConfPath() {
        return serverRoot().resolve("conf.d").resolve("mod_proxy_cluster.conf")
                .toAbsolutePath().toString();
    }

    @Override
    public boolean isRunning() {
        return processManager != null && processManager.isRunning();
    }

    @Override
    public int getInternalMcmpPort() {
        return MCMP_PORT;
    }

    @Override
    public int getMcmpSslPort() {
        return MCMP_PORT;
    }

    // ---- File I/O and command execution ----

    @Override
    public CommandResult execCommand(String... command) throws Exception {
        return NativeProcessManager.execCommand(serverRoot(), command);
    }

    @Override
    public void copyClasspathResource(String classpathResource, String destPath) {
        try {
            Path dest = Path.of(destPath);
            if (!dest.isAbsolute()) {
                dest = serverRoot().resolve(destPath);
            }
            Files.createDirectories(dest.getParent());

            URL resource = Thread.currentThread().getContextClassLoader().getResource(classpathResource);
            if (resource == null) {
                throw new RuntimeException("Classpath resource not found: " + classpathResource);
            }

            try (InputStream is = resource.openStream()) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy classpath resource '" + classpathResource + "'", e);
        }
    }

    @Override
    public void copyLocalFile(Path hostPath, String destPath) {
        try {
            Path dest = Path.of(destPath);
            if (!dest.isAbsolute()) {
                dest = serverRoot().resolve(destPath);
            }
            Files.createDirectories(dest.getParent());
            Files.copy(hostPath, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy local file '" + hostPath + "'", e);
        }
    }

    @Override
    public String getLogs() {
        return processManager != null ? processManager.readOutputLog() : "";
    }

    // ---- MCMP operations (delegated to McmpClient) ----

    @Override
    public Map<String, org.jboss.dmr.ModelNode> getWorkerInfo() throws Exception {
        Map<String, org.jboss.dmr.ModelNode> workerInfo = new HashMap<>();
        String infoResponse = mcmpClient.sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = mcmpClient.parseInfo(infoResponse);

        for (McmpClient.McmpNodeInfo node : nodes) {
            if ("REMOVED".equals(node.name)) continue;
            org.jboss.dmr.ModelNode nodeModel = new org.jboss.dmr.ModelNode();
            nodeModel.get("load").set(node.load);
            nodeModel.get("uri").set(node.type + "://" + node.host + ":" + node.port);
            nodeModel.get("load-balancing-group").set(node.lbGroup != null ? node.lbGroup : "");
            workerInfo.put(node.name, nodeModel);
        }
        return workerInfo;
    }

    @Override
    public List<String> getBalancerNames() throws Exception {
        String infoResponse = mcmpClient.sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = mcmpClient.parseInfo(infoResponse);

        Set<String> balancerNames = new LinkedHashSet<>();
        for (McmpClient.McmpNodeInfo node : nodes) {
            if ("REMOVED".equals(node.name)) continue;
            if (node.balancer != null && !node.balancer.isEmpty()) {
                balancerNames.add(node.balancer);
            }
        }
        return new ArrayList<>(balancerNames);
    }

    @Override
    public List<String> getRegisteredContexts(String nodeName) throws Exception {
        String infoResponse = mcmpClient.sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = mcmpClient.parseInfo(infoResponse);

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
    public String getContextStatus(String nodeName, String contextPath) throws Exception {
        String infoResponse = mcmpClient.sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = mcmpClient.parseInfo(infoResponse);
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
    public void disableNode(String nodeName) throws Exception {
        mcmpClient.disableNode(nodeName);
    }

    @Override
    public void stopNode(String nodeName) throws Exception {
        mcmpClient.stopNode(nodeName);
    }

    @Override
    public void enableNode(String nodeName) throws Exception {
        mcmpClient.enableNode(nodeName);
    }

    @Override
    public void removeNode(String nodeName) throws Exception {
        mcmpClient.removeNode(nodeName);
    }

    @Override
    public void enableMcmpSsl() {
        mcmpClient.enableSsl();
    }

    @Override
    public void disableContext(String nodeName, String contextPath) throws Exception {
        mcmpClient.disableApp(nodeName, contextPath, "default-host");
    }

    @Override
    public void stopContext(String nodeName, String contextPath) throws Exception {
        mcmpClient.stopApp(nodeName, contextPath, "default-host");
    }

    @Override
    public void enableContext(String nodeName, String contextPath) throws Exception {
        mcmpClient.enableApp(nodeName, contextPath, "default-host");
    }

    @Override
    public void disableLoadBalancingGroup(String groupName) throws Exception {
        List<String> nodesInGroup = findNodesInGroup(groupName);
        if (nodesInGroup.isEmpty()) {
            throw new IllegalStateException("No nodes found in group '" + groupName + "'");
        }
        for (String n : nodesInGroup) mcmpClient.disableNode(n);
    }

    @Override
    public void stopLoadBalancingGroup(String groupName) throws Exception {
        List<String> nodesInGroup = findNodesInGroup(groupName);
        if (nodesInGroup.isEmpty()) {
            throw new IllegalStateException("No nodes found in group '" + groupName + "'");
        }
        for (String n : nodesInGroup) mcmpClient.stopNode(n);
    }

    @Override
    public void enableLoadBalancingGroup(String groupName) throws Exception {
        List<String> nodesInGroup = findNodesInGroup(groupName);
        if (nodesInGroup.isEmpty()) {
            throw new IllegalStateException("No nodes found in group '" + groupName + "'");
        }
        for (String n : nodesInGroup) mcmpClient.enableNode(n);
    }

    @Override
    public void setMaxRetries(int maxRetries) throws Exception {
        log.warn("setMaxRetries({}) is a no-op on httpd balancer", maxRetries);
    }

    @Override
    public void reload() throws Exception {
        log.info("Reloading httpd balancer (graceful restart)");
        Path conf = requireConfFile();
        String serverRootStr = serverRoot().toAbsolutePath().toString();
        if (TestMode.isWindows()) {
            processManager.stop();
            List<String> command = List.of(
                    httpdBinary.toAbsolutePath().toString(),
                    "-d", serverRootStr,
                    "-f", conf.toAbsolutePath().toString(),
                    "-DFOREGROUND");
            processManager = new NativeProcessManager("httpd-balancer", command, serverRoot(), null);
            processManager.start();
        } else {
            CommandResult result = execCommand(httpdBinary.toAbsolutePath().toString(),
                    "-d", serverRootStr,
                    "-f", conf.toAbsolutePath().toString(), "-k", "graceful");
            if (!result.isSuccess()) {
                log.warn("httpd graceful restart returned exit code {}: {}",
                        result.getExitCode(), result.getStderr());
            }
        }
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .ignoreExceptions()
                .until(() -> {
                    mcmpClient.sendInfo();
                    return true;
                });
        log.info("httpd balancer reloaded successfully");
    }

    // ---- Private helpers ----

    private Path requireHttpdHome() {
        if (httpdHome == null) {
            throw new IllegalStateException("NativeHttpdBalancer has not been started");
        }
        return httpdHome;
    }

    private Path requireConfFile() {
        if (confFile == null) {
            throw new IllegalStateException("NativeHttpdBalancer has not been started");
        }
        return confFile;
    }

    /**
     * The httpd server root — the directory containing conf/, conf.d/, logs/, etc.
     * For extracted ZIPs this is httpdHome. For system httpd this is the work directory.
     */
    private Path serverRoot() {
        return requireConfFile().getParent().getParent();
    }

    /**
     * Find a JBCS httpd distribution ZIP in the {@code distributions/} directory.
     *
     * @return path to the JBCS ZIP file
     * @throws RuntimeException if no ZIP is found
     */
    private Path findJbcsZip() {
        String zipPathProp = System.getProperty("httpd.zip.path");
        if (zipPathProp != null && !zipPathProp.isBlank()) {
            Path path = Path.of(zipPathProp);
            if (Files.isRegularFile(path)) {
                return path;
            }
            throw new RuntimeException("httpd.zip.path points to non-existent file: " + zipPathProp);
        }

        File distDir = new File("distributions");
        if (distDir.exists() && distDir.isDirectory()) {
            File[] zips = distDir.listFiles((dir, name) ->
                    name.startsWith("jbcs-httpd24-") && name.endsWith(".zip"));
            if (zips != null && zips.length > 0) {
                return zips[0].toPath();
            }
        }
        throw new RuntimeException("No JBCS httpd ZIP found in distributions/. "
                + "Place a jbcs-httpd24-*.zip file there or set -Dhttpd.zip.path=<path>.");
    }

    /**
     * Extract the JBCS httpd ZIP to a per-instance directory.
     *
     * @param zipPath path to the JBCS ZIP
     * @return the httpd home directory
     */
    private Path extractJbcsZip(Path zipPath) throws IOException {
        Path instanceDir = Path.of("target", "native-servers", "httpd");

        // Detect root dir in ZIP
        String rootDir = null;
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            if (entries.hasMoreElements()) {
                String first = entries.nextElement().getName();
                int slash = first.indexOf('/');
                if (slash > 0) rootDir = first.substring(0, slash);
            }
        }

        Path home = rootDir != null ? instanceDir.resolve(rootDir) : instanceDir;
        if (Files.isDirectory(home) && findHttpdBinaryOrNull(home) != null) {
            log.info("Reusing existing httpd extraction: {}", home);
            return home;
        }

        log.info("Extracting {} to {}", zipPath.getFileName(), instanceDir);
        Files.createDirectories(instanceDir);

        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = instanceDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(instanceDir)) {
                    throw new IOException("ZIP entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zf.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        // Make httpd binary executable
        Path httpd = findHttpdBinaryOrNull(home);
        if (httpd != null) {
            httpd.toFile().setExecutable(true);
        }

        return home;
    }

    /**
     * Find the httpd binary in the extracted directory.
     *
     * @param home the httpd home directory
     * @return path to the httpd binary
     * @throws RuntimeException if not found
     */
    private Path findHttpdBinary(Path home) {
        Path binary = findHttpdBinaryOrNull(home);
        if (binary == null) {
            throw new RuntimeException("httpd binary not found in " + home
                    + ". Checked " + String.join(", ", HTTPD_BINARY_SEARCH_PATHS));
        }
        return binary;
    }

    private static final List<String> HTTPD_BINARY_SEARCH_PATHS = TestMode.isWindows()
            ? List.of("bin/httpd.exe", "sbin/httpd.exe", "httpd/bin/httpd.exe", "httpd/sbin/httpd.exe")
            : List.of("sbin/httpd", "bin/httpd", "httpd/sbin/httpd", "httpd/bin/httpd",
                    "sbin/apache2", "bin/apache2");

    private Path findHttpdBinaryOrNull(Path home) {
        for (String candidate : HTTPD_BINARY_SEARCH_PATHS) {
            Path p = home.resolve(candidate);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    /**
     * Find httpd.conf in the extracted directory, searching recursively if needed.
     *
     * @return path to httpd.conf, or {@code null} if the distribution ships without one
     *         (e.g. Windows JBCS uses fragment configs only)
     */
    private Path findHttpdConf(Path home) {
        Path conf = home.resolve("conf/httpd.conf");
        if (Files.isRegularFile(conf)) return conf;

        conf = home.resolve("etc/httpd/conf/httpd.conf");
        if (Files.isRegularFile(conf)) return conf;

        try (Stream<Path> stream = Files.walk(home)) {
            Path found = stream
                    .filter(p -> p.getFileName().toString().equals("httpd.conf"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
            if (found != null) {
                log.info("httpd.conf found at non-standard location: {}", found);
                return found;
            }
        } catch (IOException e) {
            log.warn("Error searching for httpd.conf in {}", home, e);
        }

        return null;
    }

    /**
     * Run the JBCS postinstall script if httpd.conf doesn't exist yet.
     * The JBCS distribution ships {@code .in} template files (e.g. {@code httpd.conf.in})
     * and a postinstall script that processes them into actual config files.
     */
    private void runPostinstallIfNeeded(Path home) throws IOException {
        if (findHttpdConf(home) != null) return;

        Path etcDir = home.resolve("etc");
        String scriptName = TestMode.isWindows() ? "postinstall.httpd.bat" : ".postinstall.httpd";
        Path script = etcDir.resolve(scriptName);

        if (!Files.isRegularFile(script)) {
            script = etcDir.resolve(TestMode.isWindows() ? "postinstall.bat" : ".postinstall");
        }
        if (!Files.isRegularFile(script)) {
            log.warn("No postinstall script found in {}; httpd.conf must be generated manually", etcDir);
            return;
        }

        log.info("Running postinstall script: {}", script);
        List<String> command = TestMode.isWindows()
                ? List.of("cmd", "/c", script.getFileName().toString())
                : List.of("sh", script.getFileName().toString());

        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(etcDir.toFile())
                .redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0 && exitCode != 17) {
                log.error("Postinstall script failed (exit {}): {}", exitCode, output);
                throw new RuntimeException("Postinstall script failed with exit code " + exitCode);
            }
            if (exitCode == 17) {
                log.info("Postinstall was already executed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for postinstall script", e);
        }
        log.info("Postinstall completed successfully");
    }

    /**
     * Patch httpd.conf: set Listen 8080, disable mod_proxy_balancer, include our config.
     * Also patches conf.modules.d/ fragments if proxy_balancer is loaded there.
     */
    private void patchHttpdConf() throws IOException {
        String content = Files.readString(confFile);

        content = content.replaceAll("(?m)^(Listen\\s+(?:\\S+:)?80)\\s*$", "#$1");
        content = content.replaceAll(
                "(?m)^(LoadModule proxy_balancer_module)",
                "#$1");

        if (!content.contains("Listen 8080")) {
            content += "\nListen 8080\n";
        }
        Files.writeString(confFile, content);
        log.info("httpd.conf patched for mod_proxy_cluster");

        disableProxyBalancerInFragments();
    }

    /**
     * Disable mod_proxy_balancer in conf.modules.d/ fragment configs (e.g. 00-proxy.conf).
     * mod_proxy_balancer conflicts with mod_proxy_cluster and must not be loaded.
     */
    private void disableProxyBalancerInFragments() throws IOException {
        Path confModulesD = serverRoot().resolve("conf.modules.d");
        if (!Files.isDirectory(confModulesD)) return;

        try (Stream<Path> stream = Files.list(confModulesD)) {
            for (Path fragment : stream.filter(p -> p.toString().endsWith(".conf")).toList()) {
                String content = Files.readString(fragment);
                if (content.contains("LoadModule proxy_balancer_module")) {
                    content = content.replaceAll(
                            "(?m)^(LoadModule proxy_balancer_module)",
                            "#$1");
                    Files.writeString(fragment, content);
                    log.info("Disabled proxy_balancer_module in {}", fragment.getFileName());
                }
            }
        }
    }

    /**
     * Copy mod_proxy_cluster.conf from classpath to httpd conf.d/.
     */
    private void copyModProxyClusterConf() throws IOException {
        Path destDir = serverRoot().resolve("conf.d");
        Files.createDirectories(destDir);
        Path dest = destDir.resolve("mod_proxy_cluster.conf");

        URL resource = Thread.currentThread().getContextClassLoader()
                .getResource("httpd/mod_proxy_cluster.conf");
        if (resource == null) {
            throw new RuntimeException("httpd/mod_proxy_cluster.conf not found on classpath");
        }

        try (InputStream is = resource.openStream()) {
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("mod_proxy_cluster.conf copied to {}", dest);
    }


    private List<String> findNodesInGroup(String groupName) throws IOException {
        String infoResponse = mcmpClient.sendInfo();
        List<McmpClient.McmpNodeInfo> nodes = mcmpClient.parseInfo(infoResponse);

        List<String> result = new ArrayList<>();
        for (McmpClient.McmpNodeInfo node : nodes) {
            if (groupName.equals(node.lbGroup)) {
                result.add(node.name);
            }
        }
        return result;
    }

    /**
     * Find and extract the mod_proxy_cluster connectors ZIP if available.
     * The connectors (mod_manager, mod_proxy_cluster, mod_advertise, mod_lbmethod_cluster)
     * ship in a separate {@code jbcs-httpd24-webserver-connectors-*.zip} that must be
     * overlaid into the httpd installation.
     */
    private void extractConnectorsIfAvailable(Path httpdZip) throws IOException {
        Path connectorsZip = findConnectorsZip(httpdZip);
        if (connectorsZip == null) {
            log.warn("No connectors ZIP found — mod_proxy_cluster modules may be missing. "
                    + "Place jbcs-httpd24-webserver-connectors-*.zip alongside the httpd ZIP "
                    + "or set -Dhttpd.connectors.zip.path=<path>.");
            return;
        }

        Path instanceDir = Path.of("target", "native-servers", "httpd");
        log.info("Extracting connectors from {}", connectorsZip.getFileName());
        extractOverlayZip(connectorsZip, instanceDir);
    }

    private Path findConnectorsZip(Path httpdZip) {
        String prop = System.getProperty("httpd.connectors.zip.path");
        if (prop != null && !prop.isBlank()) {
            Path p = Path.of(prop);
            if (Files.isRegularFile(p)) return p;
            log.warn("httpd.connectors.zip.path points to non-existent file: {}", prop);
        }

        Path parent = httpdZip.getParent();
        if (parent != null) {
            Path found = findConnectorsZipIn(parent);
            if (found != null) return found;
        }

        Path found = findConnectorsZipIn(Path.of("distributions"));
        if (found != null) return found;

        return null;
    }

    private Path findConnectorsZipIn(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        File[] files = dir.toFile().listFiles((d, name) ->
                name.contains("connectors") && name.endsWith(".zip"));
        if (files != null && files.length > 0) return files[0].toPath();
        return null;
    }

    private void extractOverlayZip(Path zipPath, Path targetDir) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("ZIP entry outside target: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zf.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /**
     * Remove {@code conf.d/mod_cluster-native.conf} shipped by the connectors ZIP.
     * Our own {@code mod_proxy_cluster.conf} is deployed to {@code conf.d/} and overwrites
     * the connectors' version; this method handles the separate native config file.
     */
    private void removeConflictingConfigs() throws IOException {
        Path confD = serverRoot().resolve("conf.d");
        if (!Files.isDirectory(confD)) return;

        Path modClusterNative = confD.resolve("mod_cluster-native.conf");
        if (Files.isRegularFile(modClusterNative)) {
            Files.delete(modClusterNative);
            log.info("Removed conflicting {}", modClusterNative.getFileName());
        }

        // Remove stale SSL configs from prior test classes to avoid duplicate
        // Listen directives and LoadModule conflicts on httpd restart.
        Path extraDir = confFile.getParent().resolve("extra");
        if (Files.isDirectory(extraDir)) {
            for (String sslConf : SSLConfigurator.HTTPD_SSL_CONF_FILES) {
                Path sslFile = extraDir.resolve(sslConf);
                if (Files.deleteIfExists(sslFile)) {
                    log.info("Removed stale SSL config {}", sslConf);
                }
            }
        }

        // Restore original mod_proxy_cluster.conf (may have been overwritten by SSL tests)
        try {
            copyModProxyClusterConf();
        } catch (IOException e) {
            log.warn("Failed to restore mod_proxy_cluster.conf: {}", e.getMessage());
        }
    }

    private void logHttpdDiagnostics() {
        log.error("MCMP endpoint not responding on port {}. Diagnostics:", MCMP_PORT);

        if (processManager != null) {
            log.error("httpd process alive: {}", processManager.isRunning());
            String output = processManager.readOutputLog();
            if (output != null && !output.isBlank()) {
                log.error("httpd process output:\n{}", output);
            }
        }

        if (httpdHome != null) {
            Path mDir = modulesPath != null ? modulesPath : httpdHome.resolve("modules");
            for (String module : List.of("mod_manager.so", "mod_proxy_cluster.so",
                    "mod_advertise.so", "mod_lbmethod_cluster.so")) {
                Path p = mDir.resolve(module);
                log.error("  {} -> {}", module, Files.isRegularFile(p) ? "PRESENT" : "MISSING");
            }

            Path errorLog = confFile != null
                    ? serverRoot().resolve("logs/error_log")
                    : httpdHome.resolve("logs/error_log");
            if (Files.isRegularFile(errorLog)) {
                try {
                    String errors = Files.readString(errorLog);
                    if (!errors.isBlank()) {
                        log.error("httpd error log:\n{}", errors);
                    }
                } catch (IOException e) {
                    log.warn("Could not read error log", e);
                }
            }
        }
    }
}
