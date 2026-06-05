# Testing Guide

Comprehensive guide for running mod_cluster tests with WildFly/EAP distributions.

## Table of Contents

1. [Quick Start](#quick-start)
2. [Using WildFly Distributions](#using-wildfly-distributions)
3. [Using EAP Distributions](#using-eap-distributions)
4. [Test Execution Modes](#test-execution-modes)
5. [Balancer Types](#balancer-types)
6. [Troubleshooting](#troubleshooting)

## Quick Start

```bash
# 1. Place WildFly/EAP ZIP (optional but recommended)
# The SAME ZIP is used for both workers and undertow balancer!
cp ~/Downloads/wildfly-39.0.1.Final.zip distributions/

# 2. Check prerequisites (optional)
./setup.sh

# 3. Run tests (Docker images are built automatically on first run)
mvn test
```

**What `./setup.sh` does**:
- ✓ Checks Java, Maven, Docker/Podman
- ✓ Finds ZIPs in `distributions/`

Docker images are built automatically by the test framework on first run (and cached for subsequent runs).

**Note**: When you provide a ZIP, it's used for both:
- **Workers** - WildFly/EAP instances serving applications
- **Undertow balancer** - Same WildFly/EAP, but configured as a load balancer

This ensures version consistency between workers and balancer!

## Using WildFly Distributions

### Downloading WildFly

```bash
# Download WildFly 31 (latest stable)
cd distributions/
wget https://github.com/wildfly/wildfly/releases/download/31.0.1.Final/wildfly-31.0.1.Final.zip
cd ..

# Run tests
mvn test
```

### Testing Multiple WildFly Versions

```bash
# Test with WildFly 31
mvn test -Dwildfly.zip.path=distributions/wildfly-31.0.1.Final.zip

# Test with WildFly 30
mvn test -Dwildfly.zip.path=distributions/wildfly-30.0.1.Final.zip
```

## Using EAP Distributions

### Getting EAP

EAP requires Red Hat credentials. Download from:
https://access.redhat.com/jbossnetwork/restricted/listSoftware.html

```bash
# After downloading EAP 8.0
cp ~/Downloads/jboss-eap-8.0.0.zip distributions/

# Run tests
mvn test
```

### EAP Version Support

Supported EAP versions:
- **EAP 8.x** - Based on WildFly 30+ (Jakarta EE 10)

```bash
# Test with EAP 8
mvn test -Dwildfly.zip.path=distributions/jboss-eap-8.0.0.zip
```

## Test Execution Modes

### 1. Auto-Detection (Recommended)

Place ZIP in `distributions/` - automatically detected:

```bash
cp ~/Downloads/wildfly-31.0.1.Final.zip distributions/
mvn test
```

### 2. Explicit Path

Specify exact ZIP location:

```bash
mvn test -Dwildfly.zip.path=/opt/distributions/wildfly-31.0.1.Final.zip
```

### 3. Environment Variable

Good for CI/CD:

```bash
export WILDFLY_ZIP_PATH=/opt/distributions/jboss-eap-8.0.0.zip
mvn test
```

### 4. Override Java Version

Force a specific Java version instead of auto-detection:

```bash
# Force Java 17
mvn test -Dcontainer.java.version=17

# Useful for custom builds
mvn test -Dwildfly.zip.path=/path/to/custom-wildfly.zip -Dcontainer.java.version=17
```

**When to use**:
- Custom WildFly builds
- Non-standard ZIP file names
- Testing compatibility with different Java versions
- Troubleshooting Java version issues

### 5. Fallback Mode

No ZIP provided — attempts to pull pre-built images. Note: the default `quay.io/modcluster/` images are **placeholders that do not exist yet**. Provide a ZIP or override with your own images.

```bash
mvn test -Dwildfly.version=31.0.1.Final
```

## Native Mode (No Docker)

Native mode runs WildFly and httpd as local OS processes instead of containers. Use it on Windows or any environment without Docker/Podman.

### Running in Native Mode

```bash
# Activate with the native Maven profile
mvn test -Pnative -Dwildfly.zip.path=distributions/wildfly-39.0.1.Final.zip

# Or set the system property directly
mvn test -Dtest.mode=native -Dwildfly.zip.path=distributions/wildfly-39.0.1.Final.zip
```

The `-Pnative` profile automatically:
- Sets `-Dtest.mode=native`
- Excludes tests tagged `@Tag("docker")` and `@Tag("soak")`

### httpd Balancer in Native Mode

```bash
mvn test -Pnative -Dbalancer.type=httpd \
    -Dwildfly.zip.path=distributions/wildfly-39.0.1.Final.zip \
    -Dhttpd.zip.path=distributions/jbcs-httpd24-2.4.62-RHEL9-x86_64.zip
```

The httpd ZIP is extracted and started as a local process. A connectors ZIP (mod_proxy_cluster modules) can be overlaid if provided separately.

### Port Allocation

In native mode, all processes share the host network. Each worker uses a fixed port offset via `-Djboss.socket.binding.port-offset`:

| Instance | Offset | HTTP  | HTTPS | Management | JGroups TCP |
|----------|--------|-------|-------|------------|-------------|
| balancer | 0      | 8080  | 8443  | 9990       | —           |
| worker1  | 100    | 8180  | 8543  | 10090      | 7700        |
| worker2  | 200    | 8280  | 8643  | 10190      | 7800        |
| worker3  | 300    | 8380  | 8743  | 10290      | 7900        |
| worker4  | 400    | 8480  | 8843  | 10390      | 8000        |

### Server Lifecycle

- WildFly ZIPs are extracted once per worker to `target/native-servers/{name}/` and reused across test classes
- Server configuration (`standalone-ha.xml`) is automatically reset before each test to ensure clean state
- Runtime directories (`standalone/data/`, `standalone/tmp/`) are cleaned between tests
- A JVM shutdown hook kills all native processes on exit, preventing port leaks

### Server Logs

- WildFly server log: `target/native-servers/{name}/standalone/log/server.log`
- Process stdout/stderr: `target/native-servers/{name}/process-output.log`

## Balancer Types

### Undertow Balancer (Default)

```bash
# Explicit undertow profile
mvn test -Pundertow

# Via property
mvn test -Dbalancer.type=undertow
```

### httpd Balancer

```bash
# Docker mode: builds httpd image from ZIP or source
mvn test -Phttpd

# Via property
mvn test -Dbalancer.type=httpd

# Native mode: runs httpd as a local process
mvn test -Pnative -Dbalancer.type=httpd -Dhttpd.zip.path=distributions/jbcs-httpd24.zip
```

### Custom Balancer Images

```bash
# Custom undertow
mvn test -Dbalancer.undertow.image=my-registry.com/undertow:1.0

# Custom httpd
mvn test -Phttpd -Dbalancer.httpd.image=my-registry.com/httpd:2.4
```

## Running Specific Tests

### Single Test Class

```bash
mvn test -Dtest=StickySessionTest
```

### Single Test Method

```bash
mvn test -Dtest=StickySessionTest#testStickySessionsMaintainedAcrossRequests
```

### Test Category/Package

```bash
# All failover tests
mvn test -Dtest=org.jboss.modcluster.test.failover.*

# All SSL tests
mvn test -Dtest=org.jboss.modcluster.test.ssl.*
```

### Multiple Test Classes

```bash
mvn test -Dtest=StickySessionTest,SSLTest,LoadBalancingGroupFailoverTest
```

### httpd-Only Tests

Some tests require the httpd balancer and are annotated with `@Tag("httpd")`.
These are automatically excluded when running with `-Dbalancer.type=undertow`
(the default) via the Maven undertow profile's `excludedGroups`.

```bash
# Run AJP protocol tests (requires httpd)
mvn test -Dtest=ModClusterAjpTest -Dbalancer.type=httpd
```

## Matrix Testing

### Both Balancers Sequentially

```bash
# Test with both balancers
mvn test -Pundertow && mvn test -Phttpd
```

### Parallel Execution (Linux/Mac)

```bash
# Run in parallel
mvn test -Pundertow & mvn test -Phttpd & wait
```

### Generate Matrix Report

```bash
#!/bin/bash
for balancer in undertow httpd; do
    echo "Testing with $balancer..."
    mvn test -P$balancer
    mv target/surefire-reports target/surefire-reports-$balancer
done
```

## Advanced Configuration

### Enable Debug Logging

```bash
mvn test -Dlogback.configurationFile=src/test/resources/logback-debug.xml
```

### Container Reuse (Development)

Speed up development by reusing containers:

```bash
# In src/test/resources/testcontainers.properties
testcontainers.reuse.enable=true

# Run tests
mvn test
```

⚠️ **Note**: Disable for CI (already disabled in CI profile)

### Custom Container Startup Timeout

```java
// In WildFlyContainer.java, modify:
.waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1)
    .withStartupTimeout(Duration.ofMinutes(5))) // Increase for slow systems
```

## Troubleshooting

### Issue: ZIP Not Found

```
Error: No ZIP distribution found
```

**Solution**:
```bash
# Check distributions directory
ls -la distributions/

# Verify ZIP exists
ls -la distributions/*.zip

# Use explicit path
mvn test -Dwildfly.zip.path=/full/path/to/wildfly.zip
```

### Issue: Container Build Fails

```
Error building container from ZIP
```

**Solutions**:

1. **Check ZIP integrity**:
   ```bash
   unzip -t distributions/wildfly-31.0.1.Final.zip
   ```

2. **Increase Docker memory**:
   - Docker Desktop → Settings → Resources → Memory (at least 4GB)

3. **Clean Docker cache**:
   ```bash
   docker system prune -a
   ```

### Issue: Container Startup Timeout

```
Container did not start within timeout
```

**Solutions**:

1. **Check Docker logs**:
   ```bash
   docker logs <container-id>
   ```

2. **Increase timeout** (see Advanced Configuration above)

3. **Check system resources**:
   ```bash
   docker stats
   ```

### Issue: Tests Flaky/Unstable

**Solutions**:

1. **Disable container reuse**:
   ```bash
   mvn test -Dtestcontainers.reuse.enable=false
   ```

2. **Run sequentially** (not parallel):
   ```bash
   mvn test -DforkCount=1
   ```

3. **Increase wait times** in test code using Awaitility

### Issue: Permission Denied (Linux)

```
Error: Permission denied accessing Docker socket
```

**Solution**:
```bash
# Add user to docker group
sudo usermod -aG docker $USER
newgrp docker

# Or use sudo (not recommended for regular use)
sudo mvn test
```

### Issue: Podman Socket Not Found

```
Error: Cannot connect to Podman socket
```

**Solution**:
```bash
# Enable Podman socket
systemctl --user enable --now podman.socket

# Set environment variable
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock

# Or create symlink
sudo ln -s /run/user/$(id -u)/podman/podman.sock /var/run/docker.sock
```

## Performance Tips

### Speed Up Local Development

1. **Use container reuse** (development only)
2. **Run specific tests** instead of full suite
3. **Keep distributions/ on fast storage** (SSD, not network drive)
4. **Use local Docker registry** for frequently used images

### CI/CD Optimization

1. **Cache Maven dependencies** (configured in GitHub Actions workflow)
2. **Parallel matrix builds** (configured via strategy matrix)
3. **Prune containers after tests**

## Getting Help

### Enable Verbose Logging

```bash
mvn test -X  # Maven debug mode
```

### Check Test Logs

```bash
# Test output
cat target/surefire-reports/*.txt

# Container logs (while running)
docker ps  # Get container ID
docker logs <container-id>
```

### Common Log Locations

- **Maven output**: Console
- **Test results**: `target/surefire-reports/`
- **Container logs**: Via Docker/Podman
- **Application logs**: Inside containers at `/opt/wildfly/standalone/log/`

## Best Practices

1. ✅ **Run setup.sh** to verify prerequisites before first test run
2. ✅ **Use specific ZIP paths** in CI/CD for reproducibility
3. ✅ **Clean distributions/** when switching major versions
4. ✅ **Keep only one ZIP** in distributions/ for auto-detection
5. ✅ **Disable container reuse** in CI/CD
6. ✅ **Use soft assertions** for multiple checks per test
7. ✅ **Check container logs** when debugging failures
8. ❌ **Don't commit ZIPs** to git (large files)
9. ❌ **Don't use container reuse** in CI (causes flakiness)

## Performance

### Container Reuse (Development)

For the fastest local iteration, enable Testcontainers reuse:

```properties
# In src/test/resources/testcontainers.properties
testcontainers.reuse.enable=true
```

With reuse enabled, containers stay running between test runs. The first run starts containers normally, but subsequent runs reuse them — reducing startup from minutes to seconds.

**Important**: Stop containers manually when done: `docker stop $(docker ps -aq)`

### Selective Execution

Don't run the full suite during development:

```bash
# Single test class
mvn test -Dtest=StickySessionTest

# Single test method
mvn test -Dtest=StickySessionTest#testStickySessionsMaintainedAcrossRequests

# Package
mvn test -Dtest=org.jboss.modcluster.test.failover.*
```
