package org.jboss.modcluster.test.utils;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * HTTP client utility for making requests through the balancer.
 */
public class HttpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpClient.class);

    private final OkHttpClient client;
    private final OkHttpClient insecureClient;
    private OkHttpClient trustedClient;
    private OkHttpClient mtlsClient;

    public HttpClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)    // Must exceed httpd ProxyTimeout*2 for proxy failover
                .followRedirects(false)
                .build();

        this.insecureClient = createInsecureClient();
    }

    /**
     * Perform a GET request and return the response.
     */
    public HttpResponse get(String url) throws IOException {
        return get(url, new HashMap<>());
    }

    /**
     * Perform a GET request with custom headers.
     */
    public HttpResponse get(String url, Map<String, String> headers) throws IOException {
        // Disable keep-alive: the Undertow mod_cluster filter on Windows may not
        // re-route requests on a reused connection, causing 404s or wrong-worker responses.
        headers.putIfAbsent("Connection", "close");
        Request.Builder builder = new Request.Builder().url(url);
        headers.forEach(builder::addHeader);

        try (Response response = client.newCall(builder.build()).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Perform a GET request with a custom timeout.
     * Useful for long-running operations like load generation.
     */
    public HttpResponse getWithTimeout(String url, long timeout, TimeUnit unit) throws IOException {
        OkHttpClient customClient = client.newBuilder()
                .readTimeout(timeout, unit)
                .build();

        Request request = new Request.Builder().url(url)
                .addHeader("Connection", "close")
                .build();

        try (Response response = customClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Perform a GET request with session cookie (sticky sessions).
     */
    public HttpResponse getWithSession(String url, String sessionCookie) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", sessionCookie);
        return get(url, headers);
    }

    /**
     * Perform a GET request with a session cookie and custom read timeout.
     * Use when the default 10-second read timeout is too short, such as during
     * Infinispan state transfer when a new node joins or leaves the cluster.
     *
     * @param url the URL to request
     * @param sessionCookie the session cookie value (e.g., "JSESSIONID=abc.worker1")
     * @param timeout read timeout duration
     * @param unit time unit for the timeout
     * @return the HTTP response
     * @throws IOException if the request fails
     * @see #getWithSession(String, String)
     */
    public HttpResponse getWithSession(String url, String sessionCookie,
                                       long timeout, TimeUnit unit) throws IOException {
        OkHttpClient customClient = client.newBuilder()
                .readTimeout(timeout, unit)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Cookie", sessionCookie)
                .addHeader("Connection", "close")
                .build();

        try (Response response = customClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Perform an HTTPS GET request (ignoring certificate validation).
     */
    public HttpResponse getHttps(String url) throws IOException {
        Request request = new Request.Builder().url(url)
                .addHeader("Connection", "close")
                .build();

        try (Response response = insecureClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Perform an HTTPS GET request with session cookie (ignoring certificate validation).
     */
    public HttpResponse getHttpsWithSession(String url, String sessionCookie) throws IOException {
        Request request = new Request.Builder()
            .url(url)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Connection", "close")
            .build();

        try (Response response = insecureClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Configures certificate validation using a JKS trust store from the classpath.
     * After calling this method, {@link #getHttpsTrusted(String)} and
     * {@link #getHttpsTrustedWithSession(String, String)} will validate server certificates
     * against the provided CA chain.
     *
     * <p>Hostname verification is relaxed because container hostnames are dynamic
     * in test environments. Certificate chain validation is the important part.</p>
     *
     * @param classpathResource path to JKS trust store on classpath (e.g. "ssl/ca/intermediate/keystores/ca-chain.keystore.jks")
     * @param password trust store password
     */
    public void configureTrustStore(final String classpathResource, final String password) {
        try (InputStream trustStoreStream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (trustStoreStream == null) {
                throw new IllegalArgumentException("Trust store not found on classpath: " + classpathResource);
            }

            KeyStore trustStore = KeyStore.getInstance("JKS");
            trustStore.load(trustStoreStream, password.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), new java.security.SecureRandom());

            X509TrustManager trustManager = (X509TrustManager) tmf.getTrustManagers()[0];

            this.trustedClient = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                    .hostnameVerifier((hostname, session) -> true) // container hostnames are dynamic
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build();

            log.info("Trust store configured from classpath resource: {}", classpathResource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure trust store from: " + classpathResource, e);
        }
    }

    /**
     * Perform an HTTPS GET request with certificate chain validation.
     * Requires {@link #configureTrustStore(String, String)} to be called first.
     *
     * @param url HTTPS URL to request
     * @return HTTP response
     * @throws IOException if the request fails
     * @throws IllegalStateException if trust store has not been configured
     */
    public HttpResponse getHttpsTrusted(final String url) throws IOException {
        if (trustedClient == null) {
            throw new IllegalStateException("Trust store not configured. Call configureTrustStore() first.");
        }

        Request request = new Request.Builder().url(url)
                .addHeader("Connection", "close")
                .build();

        try (Response response = trustedClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Perform an HTTPS GET request with session cookie and certificate chain validation.
     * Requires {@link #configureTrustStore(String, String)} to be called first.
     *
     * @param url HTTPS URL to request
     * @param sessionCookie session cookie value (e.g. "JSESSIONID=abc123.worker1")
     * @return HTTP response
     * @throws IOException if the request fails
     * @throws IllegalStateException if trust store has not been configured
     */
    public HttpResponse getHttpsTrustedWithSession(final String url, final String sessionCookie) throws IOException {
        if (trustedClient == null) {
            throw new IllegalStateException("Trust store not configured. Call configureTrustStore() first.");
        }

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Cookie", sessionCookie)
                .addHeader("Connection", "close")
                .build();

        try (Response response = trustedClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Configures mutual TLS (mTLS) using a trust store and a client keystore from the classpath.
     * After calling this method, {@link #getHttpsMtls(String)} will present the client certificate
     * during the TLS handshake while also validating the server certificate against the trust store.
     *
     * <p>Hostname verification is relaxed because container hostnames are dynamic
     * in test environments. Certificate chain validation is the important part.</p>
     *
     * @param trustStoreResource path to JKS trust store on classpath
     * @param trustStorePassword trust store password
     * @param clientKeystoreResource path to JKS client keystore on classpath
     * @param clientKeystorePassword client keystore password
     */
    public void configureMtlsClient(final String trustStoreResource, final String trustStorePassword,
                                     final String clientKeystoreResource, final String clientKeystorePassword) {
        try (InputStream trustStoreStream = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(trustStoreResource);
             InputStream clientKeystoreStream = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(clientKeystoreResource)) {

            if (trustStoreStream == null) {
                throw new IllegalArgumentException("Trust store not found on classpath: " + trustStoreResource);
            }
            if (clientKeystoreStream == null) {
                throw new IllegalArgumentException("Client keystore not found on classpath: " + clientKeystoreResource);
            }

            // Load trust store
            KeyStore trustStore = KeyStore.getInstance("JKS");
            trustStore.load(trustStoreStream, trustStorePassword.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);

            // Load client keystore
            KeyStore clientKeyStore = KeyStore.getInstance("JKS");
            clientKeyStore.load(clientKeystoreStream, clientKeystorePassword.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(clientKeyStore, clientKeystorePassword.toCharArray());

            // Create SSL context with both key managers and trust managers
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new java.security.SecureRandom());

            X509TrustManager trustManager = (X509TrustManager) tmf.getTrustManagers()[0];

            this.mtlsClient = new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                    .hostnameVerifier((hostname, session) -> true) // container hostnames are dynamic
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .build();

            log.info("mTLS client configured with trust store '{}' and client keystore '{}'",
                    trustStoreResource, clientKeystoreResource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to configure mTLS client", e);
        }
    }

    /**
     * Perform an HTTPS GET request with mutual TLS (client certificate authentication).
     * Requires {@link #configureMtlsClient(String, String, String, String)} to be called first.
     *
     * @param url HTTPS URL to request
     * @return HTTP response
     * @throws IOException if the request fails
     * @throws IllegalStateException if mTLS client has not been configured
     */
    public HttpResponse getHttpsMtls(final String url) throws IOException {
        if (mtlsClient == null) {
            throw new IllegalStateException("mTLS client not configured. Call configureMtlsClient() first.");
        }

        Request request = new Request.Builder().url(url)
                .addHeader("Connection", "close")
                .build();

        try (Response response = mtlsClient.newCall(request).execute()) {
            return new HttpResponse(
                    response.code(),
                    response.body() != null ? response.body().string() : "",
                    extractCookies(response),
                    extractHeaders(response)
            );
        }
    }

    /**
     * Wait until the expected number of workers are receiving traffic via the balancer.
     * Polls with testLoadDistribution until the result map has exactly expectedWorkerCount keys.
     *
     * @param url the balancer URL to send requests to
     * @param expectedWorkerCount number of distinct workers expected to receive traffic
     * @param timeout maximum time to wait
     * @return the last observed distribution map
     */
    public Map<String, Integer> waitForWorkerRegistration(String url, int expectedWorkerCount, Duration timeout) {
        AtomicReference<Map<String, Integer>> lastDistribution = new AtomicReference<>();
        await().atMost(timeout).pollInterval(ofSeconds(2))
            .untilAsserted(() -> {
                Map<String, Integer> dist = testLoadDistribution(url, expectedWorkerCount * 5);
                lastDistribution.set(dist);
                assertThat(dist.keySet().stream().filter(k -> k != null).count())
                        .as("Expected %d distinct workers but got: %s", expectedWorkerCount, dist)
                        .isEqualTo(expectedWorkerCount);
            });
        return lastDistribution.get();
    }

    /**
     * Make multiple requests to test load balancing distribution.
     * Handles connection failures gracefully (e.g., when workers are being stopped).
     * Disables connection reuse to get accurate load balancing distribution.
     */
    public Map<String, Integer> testLoadDistribution(String url, int requestCount) throws IOException {
        Map<String, Integer> workerHits = new HashMap<>();
        int successfulRequests = 0;
        int failedRequests = 0;

        for (int i = 0; i < requestCount; i++) {
            try {
                // Add "Connection: close" header to disable HTTP keep-alive and connection reuse
                // This ensures each request gets a fresh connection for accurate load balancing testing
                Map<String, String> headers = new HashMap<>();
                headers.put("Connection", "close");
                HttpResponse response = get(url, headers);
                String worker = response.getWorkerName();

                if (worker != null) {
                    workerHits.merge(worker, 1, Integer::sum);
                    successfulRequests++;
                    log.debug("Request {} -> Worker: {}", i + 1, worker);
                } else {
                    failedRequests++;
                    log.debug("Request {} returned no worker name (status {}), routing failure",
                            i + 1, response.getStatusCode());
                }
            } catch (IOException e) {
                // Handle connection failures (e.g., when worker is being stopped/unregistered)
                failedRequests++;
                log.debug("Request {} failed: {}", i + 1, e.getMessage());
                // Continue with next request
            }
        }

        log.debug("Load distribution test: {} successful, {} failed out of {} total requests",
                successfulRequests, failedRequests, requestCount);

        return workerHits;
    }

    private Map<String, String> extractCookies(Response response) {
        Map<String, String> cookies = new HashMap<>();
        response.headers("Set-Cookie").forEach(cookie -> {
            String[] parts = cookie.split(";")[0].split("=", 2);
            if (parts.length == 2) {
                cookies.put(parts[0].trim(), parts[1].trim());
            }
        });
        return cookies;
    }

    private Map<String, String> extractHeaders(Response response) {
        Map<String, String> headers = new HashMap<>();
        response.headers().toMultimap().forEach((key, values) -> {
            if (!values.isEmpty()) {
                headers.put(key, values.get(0));
            }
        });
        return headers;
    }

    /**
     * Create an insecure HTTP client that trusts all certificates (for testing only).
     */
    private OkHttpClient createInsecureClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[]{};
                        }
                    }
            };

            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                    .connectTimeout(3, TimeUnit.SECONDS)  // Reduced from 10s for faster failover detection
                    .readTimeout(5, TimeUnit.SECONDS)     // Reduced from 10s
                    .followRedirects(false)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create insecure HTTP client", e);
        }
    }

    /**
     * Response wrapper containing status, body, cookies, and headers.
     */
    public static class HttpResponse {
        private final int statusCode;
        private final String body;
        private final Map<String, String> cookies;
        private final Map<String, String> headers;

        public HttpResponse(int statusCode, String body, Map<String, String> cookies, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.cookies = cookies;
            this.headers = headers;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        public Map<String, String> getCookies() {
            return cookies;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public String getCookie(String name) {
            return cookies.get(name);
        }

        public String getHeader(String name) {
            return headers.get(name);
        }

        /**
         * Extracts the worker name ({@code jboss.node.name}) from the response body.
         * Parses the {@code <strong>Worker:</strong> workerN} tag emitted by the
         * demo and timeout-test JSP applications.
         *
         * @return worker name (e.g. "worker1"), or {@code null} if the body does not
         *         contain the expected tag
         */
        public String getWorkerName() {
            if (body != null && body.contains("<strong>Worker:</strong>")) {
                int startIdx = body.indexOf("<strong>Worker:</strong>") + "<strong>Worker:</strong>".length();
                int endIdx = body.indexOf("</p>", startIdx);
                if (endIdx > startIdx) {
                    return body.substring(startIdx, endIdx).trim();
                }
            }
            return null;
        }
    }
}
