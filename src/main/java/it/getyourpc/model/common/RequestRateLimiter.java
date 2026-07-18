package it.getyourpc.model.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@Component
public class RequestRateLimiter {
    private final RateLimitRepository repository;
    private final Cache<String, Window> localWindows;

    /**
     * Costruttore usato esclusivamente dai test unitari senza database.
     */
    public RequestRateLimiter() {
        this.repository = null;
        this.localWindows = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofHours(2))
                .build();
    }

    @Autowired
    public RequestRateLimiter(RateLimitRepository repository) {
        this.repository = repository;
        this.localWindows = null;
    }

    public void check(String scope, String clientId, int maximumRequests, Duration duration, String message) {
        if (scope == null || scope.isBlank() || scope.length() > 100) {
            throw new IllegalArgumentException("Scope del rate limit non valido");
        }
        if (maximumRequests < 1 || duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Finestra del rate limit non valida");
        }
        String identifier = clientId == null || clientId.isBlank() ? "unknown" : clientId;
        if (identifier.length() > 255) identifier = identifier.substring(0, 255);
        boolean acquired = repository == null
                ? tryAcquireLocally(scope, identifier, maximumRequests, duration)
                : repository.tryAcquire(scope, identifier, maximumRequests,
                        Math.max(1, duration.toSeconds()));
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    private boolean tryAcquireLocally(String scope, String identifier,
                                      int maximumRequests, Duration duration) {
        Window window = localWindows.get(scope + ':' + identifier, ignored -> new Window());
        return window != null && window.tryAcquire(maximumRequests, duration);
    }

    private static final class Window {
        private long startedAt = System.nanoTime();
        private int requests;

        private synchronized boolean tryAcquire(int maximumRequests, Duration duration) {
            long now = System.nanoTime();
            if (now - startedAt >= duration.toNanos()) {
                startedAt = now;
                requests = 0;
            }
            if (requests >= maximumRequests) return false;
            requests++;
            return true;
        }
    }
}
