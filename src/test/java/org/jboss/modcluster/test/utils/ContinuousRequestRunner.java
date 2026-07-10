package org.jboss.modcluster.test.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes continuous HTTP requests in a background thread.
 * Used for testing failover scenarios while requests are in-flight.
 * Tracks success/failure counts and session ID changes during continuous operation.
 * Updates session cookie from Set-Cookie headers, matching HtmlUnit WebClient behavior
 * used by the reference noe-tests implementation.
 */
public class ContinuousRequestRunner {

    private static final Logger log = LoggerFactory.getLogger(ContinuousRequestRunner.class);

    private final HttpClient httpClient;
    private final String url;
    private volatile String currentCookie;

    /**
     * Creates a continuous request runner.
     *
     * @param httpClient HTTP client to use for requests
     * @param url Target URL for requests
     * @param sessionCookie Initial session cookie value (e.g., "abc123.worker1")
     */
    public ContinuousRequestRunner(final HttpClient httpClient, final String url, final String sessionCookie) {
        this.httpClient = httpClient;
        this.url = url;
        this.currentCookie = sessionCookie;
    }

    /**
     * Starts continuous requests asynchronously in a background thread.
     * Runs for the specified duration, making requests at the specified interval.
     * Updates session cookie from Set-Cookie responses to maintain session affinity
     * after failover, analogous to how HtmlUnit WebClient handles cookies.
     *
     * @param duration Total duration to run requests
     * @param interval Interval between requests
     * @return Future containing request results when complete
     */
    public Future<RequestResult> startAsync(final Duration duration, final Duration interval) {
        final ExecutorService executor = Executors.newSingleThreadExecutor();

        return executor.submit(() -> {
            final RequestResult result = new RequestResult();
            final long endTime = System.currentTimeMillis() + duration.toMillis();
            String lastSessionId = extractSessionIdOnly(currentCookie);

            log.debug("Starting continuous requests for {} ms with {} ms interval", duration.toMillis(), interval.toMillis());

            try {
                while (System.currentTimeMillis() < endTime) {
                    try {
                        result.incrementTotal();
                        final HttpClient.HttpResponse response = httpClient.getWithSession(url, "JSESSIONID=" + currentCookie);

                        if (response.getStatusCode() == 200) {
                            result.incrementSuccess();

                            // Update cookie from Set-Cookie header if present.
                            // Server sends Set-Cookie on initial session creation and after failover
                            // (when the JVM route changes). Updating the cookie ensures subsequent
                            // requests route directly to the correct worker.
                            final String rawCookie = response.getCookie("JSESSIONID");
                            if (rawCookie != null) {
                                currentCookie = rawCookie;

                                final String currentSessionId = extractSessionIdOnly(rawCookie);
                                if (lastSessionId != null && !lastSessionId.equals(currentSessionId)) {
                                    result.incrementSessionIdChanges();
                                    log.warn("Session ID changed during continuous requests: {} -> {}", lastSessionId, currentSessionId);
                                }
                                lastSessionId = currentSessionId;
                            }

                            final String worker = response.getWorkerName();
                            if (worker != null) {
                                result.recordWorker(worker);
                            }
                        } else {
                            result.incrementFailed();
                            log.debug("Request failed with status: {}", response.getStatusCode());
                        }

                        Thread.sleep(interval.toMillis());

                    } catch (Exception e) {
                        result.incrementFailed();
                        log.debug("Request failed with exception: {}", e.getMessage());

                        try {
                            Thread.sleep(interval.toMillis());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } finally {
                executor.shutdown();
            }

            log.debug("Continuous requests completed: {} total, {} success, {} failed, {} session ID changes",
                    result.getTotalCount(), result.getSuccessCount(), result.getFailedCount(), result.getSessionIdChanges());

            return result;
        });
    }

    /**
     * Extracts session ID without JVM route.
     * JSESSIONID format: &lt;session-id&gt;.&lt;jvm-route&gt;
     * This method returns only the session ID part before the dot.
     *
     * @param cookie Cookie value
     * @return Session ID without route, or null if cookie is null
     */
    private String extractSessionIdOnly(final String cookie) {
        if (cookie == null) {
            return null;
        }
        final int dotIndex = cookie.indexOf('.');
        return dotIndex > 0 ? cookie.substring(0, dotIndex) : cookie;
    }

    /**
     * Results from continuous request execution.
     * Thread-safe counters for tracking request outcomes.
     */
    public static class RequestResult {
        private final AtomicInteger totalCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger failedCount = new AtomicInteger(0);
        private final AtomicInteger sessionIdChanges = new AtomicInteger(0);
        private volatile String firstWorker;
        private volatile String lastWorker;

        void incrementTotal() {
            totalCount.incrementAndGet();
        }

        void incrementSuccess() {
            successCount.incrementAndGet();
        }

        void incrementFailed() {
            failedCount.incrementAndGet();
        }

        void incrementSessionIdChanges() {
            sessionIdChanges.incrementAndGet();
        }

        void recordWorker(final String worker) {
            if (firstWorker == null) {
                firstWorker = worker;
            }
            lastWorker = worker;
        }

        public int getTotalCount() {
            return totalCount.get();
        }

        public int getSuccessCount() {
            return successCount.get();
        }

        public int getFailedCount() {
            return failedCount.get();
        }

        public int getSessionIdChanges() {
            return sessionIdChanges.get();
        }

        public String getFirstWorker() {
            return firstWorker;
        }

        public String getLastWorker() {
            return lastWorker;
        }
    }
}
