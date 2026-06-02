# mod_cluster Test Suite

[![mod_cluster Tests](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

Comprehensive test suite for mod_cluster with WildFly/EAP workers and Undertow/httpd balancers.

## Architecture

This test suite uses:
- **JUnit 5** for test framework
- **AssertJ** for soft assertions
- **Testcontainers** for container-based testing (Docker mode, default)
- **Native process management** for non-container testing (native mode, for Windows / no-Docker environments)
- **Creaper** for WildFly/EAP management (clean, type-safe API)
- **Dependency Injection** pattern (no abstract base classes)

## Project Structure

```
src/test/java/org/jboss/modcluster/test/
├── apps/                      # Test application endpoints
│   ├── ejb/                  # EJB beans, client, and builders
│   └── ...                   # WebSocket, demo app, etc.
├── base/                      # Core test infrastructure
│   ├── BalancerType.java     # Balancer type enum
│   └── ModClusterTestExtension.java  # JUnit 5 extension for DI
├── cli/                       # CLI & management tests
│   ├── CliManagementTest.java
│   └── MultipleUndertowServerSupportTest.java
├── configuration/             # Configuration tests
│   ├── DynamicReconfTest.java
│   ├── InitialLoadTest.java
│   ├── SettingsTest.java
│   └── WorkerWithOneNotRespondingProxyTest.java
├── context/                   # Context lifecycle tests
│   └── ContextLifecycleTest.java
├── ejb/                       # EJB over HTTP tests
│   └── EjbViaHttpTest.java
├── failover/                  # Failover scenarios
│   ├── AdvancedFailoverTest.java
│   ├── FailoverSettingsTest.java
│   ├── StickySessionTest.java
│   └── WebSocketsTest.java
├── ha/                        # High availability & soak tests
│   ├── HighAvailabilityTest.java
│   └── SoakTest.java
├── loadbalancing/             # Load balancing tests
│   ├── LoadBalancingGroupFailoverTest.java
│   └── LoadMetricsTest.java
├── session/                   # Session management tests
│   └── SessionManagementTest.java
├── auth/                      # Authentication propagation tests
│   ├── AjpAuthConfigurator.java
│   └── AjpAuthPropagationTest.java
├── ssl/                       # SSL/TLS tests
│   ├── SslCrlTest.java
│   ├── SslFailoverTest.java
│   └── SslWorkerAuthenticationTest.java
└── utils/                     # Utilities
    ├── balancer/              # Balancer implementations
    │   ├── NativeHttpdBalancer.java
    │   └── NativeUndertowBalancer.java
    ├── NativeProcessManager.java   # OS process lifecycle (start/stop/kill)
    ├── NativeServerExtractor.java  # ZIP extraction for native mode
    ├── NativePortAllocator.java    # Static port offsets for native mode
    ├── NativeWildFlyWorker.java    # Native WildFly worker
    ├── WildFlyContainer.java
    ├── HttpClient.java
    └── ...
```

## Running Tests

### Prerequisites

- Java 17 or higher
- Maven 3.6+
- Docker or Podman (Docker mode only; not required for native mode)
- WildFly or EAP ZIP distribution (optional in Docker mode, required in native mode)

### Quick Start

1. **Place your WildFly/EAP ZIP in the distributions directory**:
   ```bash
   cp ~/Downloads/wildfly-39.0.1.Final.zip distributions/
   # or
   cp ~/Downloads/jboss-eap-8.0.0.zip distributions/
   ```

   Alternatively, download WildFly from Maven Central:
   ```bash
   mvn generate-test-resources -Pdownload-wildfly -Dwildfly.version=39.0.1.Final -DskipTests
   ```

2. **Check prerequisites** (optional):
   ```bash
   ./setup.sh
   ```

3. **Run tests** (Docker images are built automatically on first run):
   ```bash
   mvn test
   ```

### Run all tests with Undertow balancer (default)

```bash
mvn test
```

### Using specific ZIP distribution

```bash
# Via system property
mvn test -Dwildfly.zip.path=/path/to/wildfly-31.0.1.Final.zip

# Via environment variable
export WILDFLY_ZIP_PATH=/path/to/jboss-eap-8.0.0.zip
mvn test
```

### Override Java version

```bash
# During setup
CONTAINER_JAVA_VERSION=17 ./setup.sh

# During tests
mvn test -Dcontainer.java.version=17

# Or combine both
CONTAINER_JAVA_VERSION=17 ./setup.sh
mvn test -Dcontainer.java.version=17
```

### Run tests with httpd balancer

```bash
mvn test -Phttpd
```

or

```bash
mvn test -Dbalancer.type=httpd
```

### Native Mode (no Docker)

Run tests without Docker/Podman by starting WildFly and httpd as local OS processes:

```bash
# Undertow balancer (default)
mvn test -Pnative -Dwildfly.zip.path=distributions/wildfly-39.0.1.Final.zip

# httpd balancer (JBCS ZIP)
mvn test -Pnative -Dbalancer.type=httpd \
    -Dwildfly.zip.path=distributions/wildfly-39.0.1.Final.zip \
    -Dhttpd.zip.path=distributions/jbcs-httpd24-2.4.62-win-x86_64.zip
```

#### System httpd (no ZIP required)

You can use a system-installed httpd instead of a JBCS ZIP. This requires building
mod_proxy_cluster modules from source.

**Prerequisites** (Fedora/RHEL):
```bash
sudo dnf install httpd httpd-devel apr-devel apr-util-devel mod_ssl cmake gcc
```

**Prerequisites** (Debian/Ubuntu):
```bash
sudo apt-get install apache2-dev libapr1-dev libaprutil1-dev cmake gcc
```

**Build mod_proxy_cluster modules:**
```bash
git clone --depth 1 https://github.com/modcluster/mod_proxy_cluster.git target/mod_proxy_cluster
cmake -S target/mod_proxy_cluster/native -B target/mod_proxy_cluster/native/build -DCMAKE_BUILD_TYPE=Debug
make -C target/mod_proxy_cluster/native/build -j$(nproc)
```

**Run tests:**
```bash
mvn test -Pnative -Dbalancer.type=httpd \
    -Dhttpd.home=/usr \
    -Dhttpd.modules.path=$PWD/target/mod_proxy_cluster/native/build/modules
```

The `-Pnative` profile sets `-Dtest.mode=native` and excludes `@Tag("docker")` and `@Tag("soak")` tests. See [TESTING.md](TESTING.md) for details on port allocation and server lifecycle.

### Run specific test class

```bash
mvn test -Dtest=StickySessionTest
```

### Run with specific WildFly version

```bash
mvn test -Dwildfly.version=31.0.1.Final
```

## Writing Tests

### Basic Test Structure

Tests use dependency injection via JUnit 5 extensions:

```java
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class MyTest {

    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testSomething(TestCluster cluster, HttpClient httpClient) throws Exception {
        // Start workers
        cluster.startWorkers(2);

        // Get balancer URL
        String url = cluster.getBalancer().getHttpUrl() + "/demo";

        // Make requests
        HttpResponse response = httpClient.get(url);

        // Assertions
        softly.assertThat(response.getStatusCode()).isEqualTo(200);
    }
}
```

### Injected Dependencies

- `TestCluster cluster` - Provides access to balancer and workers
- `HttpClient httpClient` - HTTP client for making requests
- `BalancerContainer balancer` - Direct balancer access
- `@InjectSoftAssertions SoftAssertions softly` - Soft assertions

### Starting Workers

```java
// Start 1 worker
cluster.startWorkers(1);
WildFlyContainer worker = cluster.getWorker1();

// Start 2 workers
cluster.startWorkers(2);
WildFlyContainer worker1 = cluster.getWorker1();
WildFlyContainer worker2 = cluster.getWorker2();
```

### Making HTTP Requests

```java
// Simple GET
HttpResponse response = httpClient.get(url);

// GET with session
HttpResponse response = httpClient.getWithSession(url, "JSESSIONID=" + sessionId);

// HTTPS request
HttpResponse response = httpClient.getHttps(httpsUrl);

// Test load distribution
Map<String, Integer> distribution = httpClient.testLoadDistribution(url, 100);
```

### Using WildFly CLI

```java
WildFlyContainer worker = cluster.getWorker1();
String result = worker.executeCli("/subsystem=modcluster:read-resource");
```

## Test Categories

### CLI & Management Tests
- **CliManagementTest** - CLI operations, configuration read/write, deployment status
- **MultipleUndertowServerSupportTest** - Multiple Undertow server instances

### Failover Tests
- **AdvancedFailoverTest** - Failover with active sessions, deterministic and graceful failover
- **FailoverSettingsTest** - Failover configuration options
- **StickySessionTest** - Session affinity across requests
- **WebSocketsTest** - WebSocket proxying and failover

### SSL Tests
- **SslCrlTest** - Certificate Revocation List validation
- **SslFailoverTest** - SSL with failover scenarios
- **SslWorkerAuthenticationTest** - Mutual SSL authentication

### Authentication Tests
- **AjpAuthPropagationTest** - REMOTE_USER propagation via AJP (Elytron EXTERNAL mechanism)

### Load Balancing Tests
- **LoadBalancingGroupFailoverTest** - Load distribution and group failover
- **LoadMetricsTest** - Load metrics calculation and custom metrics

### Configuration Tests
- **DynamicReconfTest** - Dynamic worker registration and reconfiguration
- **InitialLoadTest** - Initial load reporting
- **SettingsTest** - Configuration settings validation
- **WorkerWithOneNotRespondingProxyTest** - Proxy resilience

### Context Tests
- **ContextLifecycleTest** - Context enable/disable, exclusion patterns, deployment registration

### Session Tests
- **SessionManagementTest** - Session timeout, custom cookies, JVM routes

### EJB over HTTP Tests
- **EjbViaHttpTest** - HTTP invoker endpoint registration, stateful EJB stickiness with failover, stateless EJB invocation

### High Availability Tests
- **HighAvailabilityTest** - Hot standby, multiple balancers
- **SoakTest** - Long-running stability testing

## Test Coverage

### Current Status vs noe-tests

This test suite aims for feature parity with `noe-tests/modcluster` (64 test files). The following table shows coverage status:

| Area | Implemented | Key Test Classes |
|------|-------------|-----------------|
| CLI & Management | Yes | CliManagementTest, MultipleUndertowServerSupportTest |
| Sticky Sessions | Yes | StickySessionTest |
| Advanced Failover | Yes | AdvancedFailoverTest, FailoverSettingsTest |
| Load Balancing | Yes | LoadBalancingGroupFailoverTest, LoadMetricsTest |
| SSL/TLS | Yes | SslCrlTest, SslFailoverTest, SslWorkerAuthenticationTest |
| Authentication (AJP) | Yes | AjpAuthPropagationTest |
| Dynamic Reconfiguration | Yes | DynamicReconfTest, SettingsTest |
| Context Lifecycle | Yes | ContextLifecycleTest |
| Session Management | Yes | SessionManagementTest |
| High Availability | Yes | HighAvailabilityTest |
| WebSockets | Yes | WebSocketsTest |
| Initial Load | Yes | InitialLoadTest |
| EJB over HTTP | Yes | EjbViaHttpTest |
| Soak/Stress Testing | Yes | SoakTest |

### Not Yet Implemented

| Area | noe-tests Reference |
|------|-------------------|
| AJP Protocol (beyond auth) | ModClusterAJP.groovy |
| mod_proxy / mod_rewrite | ModProxyTest.groovy, ModRewriteTest.groovy |
| Bug-specific regressions | JBCS*, JBQA* test files |

## How It Works

### WildFly/EAP Distribution (Single ZIP for Everything!)

**The same WildFly/EAP ZIP is used for both workers AND the Undertow balancer** - just with different configurations:

1. Tests look for ZIP distributions in the `distributions/` directory
2. If found, builds Docker images using `docker build` (avoids Testcontainers large file limitations):
   - Checks if image already exists (reuses if available)
   - If not, runs `docker build` directly with the ZIP
   - Uses Red Hat UBI9 with OpenJDK by default (version auto-detected based on WildFly/EAP version); override with `-Dcontainer.base.image`
     - **WildFly 27+ / EAP 8+**: Uses OpenJDK 17
   - Extracts the ZIP inside the image
   - **Same image used for both workers and balancer** (configuration differs at runtime)
   - **For workers**: Starts with `standalone-ha.xml`, connects to balancer
   - **For Undertow balancer**: Starts with `standalone-ha.xml`, acts as load balancer (advertise enabled)
3. If no ZIP is found, falls back to pre-built container images

**Image naming**: `modcluster-test/wildfly-31-0-1-final:ubi9-openjdk-17`

### Clustering (JGroups)

WildFly uses JGroups for worker-to-worker session replication. The default `standalone-ha.xml` uses UDP multicast for cluster discovery, which does not work in Docker/Podman networks. The test framework automatically reconfigures JGroups at startup:

1. **Binds the private interface to `0.0.0.0`** (`-bprivate 0.0.0.0`) so JGroups TCP listens on the correct network interface instead of `127.0.0.1`
2. **Switches from UDP to the TCP stack** and replaces MPING (multicast discovery) with **TCPPING**

The TCPPING `initial_hosts` are mode-dependent:
- **Docker**: container hostnames with the base port — `worker1[7600],worker2[7600],...`
- **Native**: `localhost` with offset ports — `localhost[7700],localhost[7800],...`

This is transparent to the tests — JGroups handles internal session replication while mod_cluster handles balancer-to-worker communication via MCMP over HTTP. The two layers are independent.

> **Note:** The reference noe-tests achieve the same result differently — they set `AS7_PRIVATE_IP_ADDRESS` to a real IP and rely on native multicast, which works on bare metal/VM networks.

### Balancers
- **Undertow balancer**:
  - **With ZIP**: Builds from your WildFly/EAP ZIP (same as workers)
  - **Without ZIP**: Falls back to a pre-built image (placeholder: `quay.io/modcluster/mod_cluster-undertow:latest` — does not exist yet, provide your own via `-Dbalancer.undertow.image=`)
  - Customizable via `-Dbalancer.undertow.image=`
- **httpd balancer**:
  - **Docker mode** (default):
    - **With httpd ZIP** (`-Dhttpd.zip.path=`): Builds from a pre-built httpd ZIP (e.g. JBCS). Auto-detects RHEL version from ZIP filename for the base image.
    - **Without ZIP**: Builds httpd from source and compiles mod_proxy_cluster modules (uses `fedora:42` as base)
    - **Pre-built image**: Override with `-Dbalancer.httpd.image=` to skip building entirely
  - **Native mode**:
    - **System httpd** (`-Dhttpd.home=/usr`): Uses system-installed httpd with externally-built mod_proxy_cluster modules (`-Dhttpd.modules.path=`)
    - **JBCS ZIP** (`-Dhttpd.zip.path=`): Extracts and runs directly as a local process

### ZIP Distribution Priority
1. System property: `-Dwildfly.zip.path=/path/to/wildfly.zip`
2. Environment variable: `WILDFLY_ZIP_PATH=/path/to/wildfly.zip`
3. Convention: First ZIP found in `distributions/` directory
4. Fallback: Pre-built container images

## Container Images

Default fallback images (when no ZIP provided). The `quay.io/modcluster/` images are **placeholders that do not exist yet** — provide a ZIP or override with your own images.

| Component | Default Image (placeholder) | Override |
|-----------|---------------------------|----------|
| Undertow balancer | `quay.io/modcluster/mod_cluster-undertow:latest` | `-Dbalancer.undertow.image=` |
| httpd balancer | `quay.io/modcluster/mod_cluster-httpd:latest` | `-Dbalancer.httpd.image=` |
| WildFly workers | `quay.io/wildfly/wildfly:<version>` | Provide a ZIP in `distributions/` |

In practice, always provide a WildFly/EAP ZIP — the fallback images are not published.

## Configuration Properties

| Property | Mode | Default | Description |
|---|---|---|---|
| `test.mode` | All | `docker` | `docker` or `native` |
| `balancer.type` | All | `undertow` | `undertow` or `httpd` |
| `wildfly.zip.path` | All | auto-detect in `distributions/` | Path to WildFly/EAP ZIP |
| `wildfly.version` | Docker | — | WildFly version to download from Maven Central |
| `httpd.home` | Native | derived from ZIP extraction | Path to httpd installation root (e.g. `/usr`) |
| `httpd.zip.path` | Both | auto-detect in `distributions/` | Path to JBCS httpd ZIP |
| `httpd.connectors.zip.path` | Native | auto-detect alongside httpd ZIP | Path to JBCS connectors ZIP |
| `httpd.modules.path` | Native | `httpdHome/modules` | Directory containing mod_proxy_cluster `.so` files |
| `httpd.skip.mod_proxy_cluster` | Native | `false` | Skip mod_proxy_cluster modules (for direct AJP proxy tests) |
| `httpd.version` | Docker | `2.4.66` | httpd version for Docker source build |
| `balancer.httpd.image` | Docker | built automatically | Custom Docker image for httpd balancer |
| `balancer.undertow.image` | Docker | built from WildFly ZIP | Custom Docker image for Undertow balancer |
| `mod.proxy.cluster.repo.url` | Docker | `https://github.com/modcluster/mod_proxy_cluster.git` | mod_proxy_cluster source repo |

## Contributing

When adding new tests:
1. Choose appropriate package based on test category
2. Use `@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})`
3. Inject `SoftAssertions` for assertions
4. Inject `TestCluster` and `HttpClient` as needed
5. Document test purpose in class javadoc
6. Follow existing naming conventions

See [CONTRIBUTING.adoc](CONTRIBUTING.adoc) for detailed guidelines.

## License

See LICENSE file for details.
