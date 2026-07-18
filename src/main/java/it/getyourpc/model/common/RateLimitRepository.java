package it.getyourpc.model.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class RateLimitRepository {
    private final JdbcClient jdbcClient;

    public RateLimitRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean tryAcquire(String scope, String clientId, int maximumRequests, long windowSeconds) {
        return jdbcClient.sql("""
                        INSERT INTO RequestRateLimit
                            (scope, client_id, window_started_at, requests)
                        VALUES (:scope, :clientId, CURRENT_TIMESTAMP, 1)
                        ON CONFLICT (scope, client_id) DO UPDATE SET
                            window_started_at = CASE
                                WHEN RequestRateLimit.window_started_at <=
                                     CURRENT_TIMESTAMP - make_interval(secs => :windowSeconds)
                                THEN CURRENT_TIMESTAMP
                                ELSE RequestRateLimit.window_started_at
                            END,
                            requests = CASE
                                WHEN RequestRateLimit.window_started_at <=
                                     CURRENT_TIMESTAMP - make_interval(secs => :windowSeconds)
                                THEN 1
                                ELSE RequestRateLimit.requests + 1
                            END
                        WHERE RequestRateLimit.window_started_at <=
                              CURRENT_TIMESTAMP - make_interval(secs => :windowSeconds)
                           OR RequestRateLimit.requests < :maximumRequests
                        RETURNING requests
                        """)
                .param("scope", scope)
                .param("clientId", clientId)
                .param("windowSeconds", windowSeconds)
                .param("maximumRequests", maximumRequests)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbcClient.sql("""
                        DELETE FROM RequestRateLimit
                        WHERE window_started_at < :cutoff
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
