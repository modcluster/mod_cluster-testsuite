# Architecture Overview

## System Design

### Single ZIP, Multiple Roles

The test framework uses **one WildFly/EAP ZIP distribution** for multiple purposes:

```
distributions/wildfly-31.0.1.Final.zip
         │
         ├──> Undertow Balancer (mod_cluster proxy)
         │    └─ standalone-ha.xml with advertise=true
         │
         ├──> Worker 1 (application server)
         │    └─ standalone-ha.xml, connects to balancer
         │
         └──> Worker 2 (application server)
              └─ standalone-ha.xml, connects to balancer
```

### Container Build Process

When you run tests with a ZIP:

1. **Detection Phase**
   - Checks `wildfly.zip.path` system property
   - Checks `WILDFLY_ZIP_PATH` environment variable
   - Scans `distributions/` directory

2. **Build Phase** (per component)
   ```dockerfile
   FROM registry.access.redhat.com/ubi9/openjdk-17:latest
   COPY wildfly-31.0.1.Final.zip /opt/
   RUN unzip wildfly-31.0.1.Final.zip
   # ... configuration specific to role (balancer vs worker)
   ```

3. **Runtime Phase**
   - **Balancer**: `standalone.sh -Djboss.modcluster.advertise=true`
   - **Workers**: `standalone.sh` connecting to `balancer:8090`

## Test Modes

The test suite supports two execution modes, selected via `-Dtest.mode=` (or the `-Pnative` Maven profile):

### Docker Mode (default)

Each worker and balancer runs in its own Docker/Podman container managed by Testcontainers. Containers share a private Docker network with DNS aliases (`worker1`, `worker2`, `balancer`). All containers use identical ports (8080, 9990, 7600) — networking separates them.

### Native Mode (`-Dtest.mode=native`)

Each worker and balancer runs as a local OS process started via `ProcessBuilder`. All processes share the host network and are distinguished by static port offsets (e.g. worker1 at offset 100, worker2 at offset 200). No container runtime is required.

Key native-mode components:
- **`NativeProcessManager`** — wraps `ProcessBuilder`/`Process` for lifecycle management (start, stop, kill, process tree cleanup)
- **`NativeServerExtractor`** — extracts WildFly ZIP to `target/native-servers/{name}/`, backs up clean config for per-test reset
- **`NativePortAllocator`** — assigns fixed port offsets per worker name
- **`NativeWildFlyWorker`** — native WildFly worker implementation (extends `WildFlyWorker`)
- **`NativeUndertowBalancer`** — native Undertow balancer (WildFly process with mod_cluster proxy)
- **`NativeHttpdBalancer`** — native httpd balancer (JBCS httpd process with mod_proxy_cluster)

## Component Architecture

### Test Extension (Dependency Injection)

```java
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class MyTest {
    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void test(TestCluster cluster, HttpClient client) {
        // cluster and client injected automatically
    }
}
```

**Lifecycle**:
1. `@BeforeEach` - Creates balancer, network, HTTP client
2. Test execution - Injects dependencies
3. `@AfterEach` - Stops all containers, runs soft assertions

### Container Hierarchy

```
Network: modcluster-test-network
    │
    ├─ balancer (alias: "balancer")
    │  ├─ Port 8080 (HTTP)
    │  ├─ Port 8443 (HTTPS)
    │  └─ Port 8090 (MCMP - ModCluster Management Protocol)
    │
    ├─ worker1 (alias: "worker1")
    │  ├─ Port 8080 (HTTP)
    │  ├─ Port 8443 (HTTPS)
    │  ├─ Port 9990 (Management)
    │  └─ Env: WILDFLY_MODCLUSTER_PROXY_LIST=balancer:8090
    │
    └─ worker2 (alias: "worker2")
       ├─ Port 8080 (HTTP)
       ├─ Port 8443 (HTTPS)
       ├─ Port 9990 (Management)
       └─ Env: WILDFLY_MODCLUSTER_PROXY_LIST=balancer:8090
```

### Communication Flow

```
Client Request
    │
    ▼
[HttpClient]
    │
    ▼
[Balancer Container :8080] ──MCMP──> [Worker1 :8090]
    │                                      │
    │                                      ▼
    └──────HTTP Proxy──────────> [Worker1 :8080] ──> Application
                                          │
                                          └──> Session: JSESSIONID.worker1
```

## Balancer Types

### Undertow (WildFly-based)

**From ZIP**:
```java
// Same ZIP as workers
ImageFromDockerfile()
    .withDockerfile(...)
    .withFileFromPath("wildfly.zip", zipPath)

// Started as:
standalone.sh -Djboss.modcluster.advertise=true
```

**Advantages**:
- ✅ Version consistency with workers
- ✅ Same mod_cluster implementation
- ✅ Native integration
- ✅ Easy CLI management

**Use when**: Testing with specific EAP/WildFly versions

### httpd (Apache-based)

**From Image**:
```java
// Placeholder — override via -Dbalancer.httpd.image=
DockerImageName.parse("quay.io/modcluster/mod_cluster-httpd:latest")
```

**Advantages**:
- ✅ Production-like (httpd commonly used as edge proxy)
- ✅ Tests httpd-specific behavior
- ✅ C-based mod_cluster implementation

**Use when**: Testing httpd integration, production scenarios

## Test Categories

### 1. CLI Tests (`org.jboss.modcluster.test.cli`)
- Management operations via WildFly CLI
- Configuration changes
- Runtime operations

**Example**: `AS7CLITest.java`

### 2. Failover Tests (`org.jboss.modcluster.test.failover`)
- Worker failure scenarios
- Session failover
- Graceful/ungraceful shutdown

**Example**: `StickySessionTest.java`

### 3. Load Balancing (`org.jboss.modcluster.test.loadbalancing`)
- Request distribution
- Load metrics
- Balancing algorithms

**Example**: `LoadBalancingGroupFailoverTest.java`

### 4. SSL/TLS (`org.jboss.modcluster.test.ssl`)
- HTTPS connectivity
- Certificate handling
- Worker authentication

**Example**: `SSLTest.java`

### 5. Configuration (`org.jboss.modcluster.test.configuration`)
- Dynamic reconfiguration
- Settings validation
- Multi-balancer setups

**Example**: `DynamicReconfTest.java`

### 6. Context Management (`org.jboss.modcluster.test.context`)
- Context lifecycle
- Exclusions
- Delimiters

**To be implemented**

### 7. Session Management (`org.jboss.modcluster.test.session`)
- Timeouts
- Cookie handling
- Session affinity

**To be implemented**

### 8. AJP Protocol (`org.jboss.modcluster.test.ajp`)
- AJP data path through mod_cluster
- Worker registration with AJP scheme
- End-to-end request proxying via mod_proxy_ajp

**Example**: `ModClusterAjpTest.java` (httpd only — `@Tag("httpd")`)

### 9. Integration (`org.jboss.modcluster.test.integration`)
- EJB over HTTP
- WebSockets
- Full application scenarios

**To be implemented**

## Jenkins Matrix Execution

### Matrix Axes

```groovy
matrix {
    axes {
        axis {
            name 'BALANCER_TYPE'
            values 'undertow', 'httpd'
        }
    }
}
```

### Execution Flow

```
Jenkins Pipeline
    │
    ├─ Build Stage
    │  └─ mvn compile
    │
    └─ Test Matrix
       │
       ├─ [undertow]
       │  └─ mvn test -Pundertow -Dwildfly.zip.path=...
       │     ├─ AS7CLITest
       │     ├─ StickySessionTest
       │     ├─ LoadBalancingGroupFailoverTest
       │     └─ ...
       │
       └─ [httpd]
          └─ mvn test -Phttpd -Dwildfly.zip.path=...
             ├─ AS7CLITest
             ├─ StickySessionTest
             ├─ LoadBalancingGroupFailoverTest
             └─ ...
```

### Results Matrix

| Test Class | undertow | httpd |
|-----------|----------|-------|
| AS7CLITest | ✓ | ✓ |
| StickySessionTest | ✓ | ✓ |
| LoadBalancingGroupFailoverTest | ✓ | ✓ |
| SSLTest | ✓ | ✓ |
| DynamicReconfTest | ✓ | ✓ |
| ModClusterAjpTest | — | ✓ |

## Configuration Points

### System Properties

| Property | Purpose | Example |
|----------|---------|---------|
| `wildfly.zip.path` | ZIP location | `/opt/wildfly-31.0.1.Final.zip` |
| `wildfly.version` | Fallback version | `31.0.1.Final` |
| `container.java.version` | Override Java version | `17` or `11` |
| `balancer.type` | Balancer type | `undertow` or `httpd` |
| `balancer.undertow.image` | Custom undertow | `my-registry.com/undertow:1.0` |
| `balancer.httpd.image` | Custom httpd | `my-registry.com/httpd:2.4` |

### Environment Variables

| Variable | Purpose | Example |
|----------|---------|---------|
| `WILDFLY_ZIP_PATH` | ZIP location | `/opt/wildfly-31.0.1.Final.zip` |

### Maven Profiles

| Profile | Purpose | Activation |
|---------|---------|------------|
| `undertow` | Undertow balancer | Default / `-Pundertow` |
| `httpd` | httpd balancer | `-Phttpd` |
| `ci` | CI environment | `-Pci` |

## Dependency Graph

```
pom.xml
  ├─ JUnit 5 (test framework)
  ├─ AssertJ (soft assertions)
  ├─ Testcontainers (container orchestration)
  ├─ WildFly CLI (management client)
  ├─ OkHttp (HTTP client)
  └─ Awaitility (async testing)

ModClusterTestExtension.java
  ├─ Balancer (abstract)
  │   ├─ Docker: UndertowBalancerContainer, HttpdBalancerContainer
  │   └─ Native: NativeUndertowBalancer, NativeHttpdBalancer
  ├─ WildFlyWorker (abstract)
  │   ├─ Docker: DockerWildFlyWorker
  │   └─ Native: NativeWildFlyWorker
  ├─ NativeProcessManager (process lifecycle)
  ├─ NativeServerExtractor (ZIP extraction)
  ├─ NativePortAllocator (port offsets)
  └─ HttpClient.java

Test Classes
  └─ Extend with ModClusterTestExtension
     └─ Inject: TestCluster, HttpClient, SoftAssertions
```

## Performance Characteristics

### Container Startup Times

| Component | From ZIP | From Image |
|-----------|----------|------------|
| Undertow Balancer | ~60-90s | ~30s |
| httpd Balancer | N/A | ~10s |
| WildFly Worker | ~60-90s | ~45s |

**Full test setup** (1 balancer + 2 workers from ZIP): ~2-3 minutes

### Optimization Strategies

1. **Container Reuse** (dev only)
   - Enable: `testcontainers.reuse.enable=true`
   - Saves: ~2 minutes per test run
   - ⚠️ Not for CI!

2. **Image Caching**
   - First run: Builds images from ZIP
   - Subsequent: Reuses Docker layer cache
   - Saves: ~30-60s per test run

3. **Parallel Tests**
   - Maven: `-DforkCount=2`
   - Can run multiple test classes simultaneously

## Troubleshooting Decision Tree

```
Test Failed?
    │
    ├─ Container didn't start?
    │  ├─ Check: docker logs <container-id>
    │  ├─ Check: Timeout sufficient? (default 3 min)
    │  └─ Check: Docker memory >= 4GB
    │
    ├─ Test assertion failed?
    │  ├─ Check: Soft assertions output (all failures)
    │  ├─ Check: Test logs (target/surefire-reports/)
    │  └─ Enable: Debug logging (-X)
    │
    └─ ZIP not found?
       ├─ Check: ls distributions/*.zip
       ├─ Check: System property set correctly
       └─ Fallback: Will use pre-built images
```

## Extension Points

### Adding New Test

1. Choose package based on category
2. Add `@ExtendWith` annotation
3. Inject dependencies
4. Write test using AssertJ soft assertions

```java
@ExtendWith({ModClusterTestExtension.class, SoftAssertionsExtension.class})
public class MyNewTest {
    @InjectSoftAssertions
    private SoftAssertions softly;

    @Test
    public void testFeature(TestCluster cluster, HttpClient client) {
        cluster.startWorkers(2);
        // ... test logic ...
        softly.assertThat(result).isEqualTo(expected);
    }
}
```

### Custom Container Configuration

Extend `WildFlyContainer` or `BalancerContainer`:

```java
public class CustomWildFlyContainer extends WildFlyContainer {
    @Override
    public void start() {
        // Custom configuration
        super.start();
        // Post-start customization
    }
}
```

### Custom Deployment

```java
@Test
public void testWithCustomApp(TestCluster cluster) {
    cluster.startWorkers(1);

    File war = DemoAppBuilder.createDemoApp();
    cluster.getWorker1().deployment().deploy(war, "custom.war");

    // Test with deployment
}
```
