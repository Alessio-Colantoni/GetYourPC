package it.getyourpc.model.common;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestRateLimiterTest {
    @Test
    void rejectsRequestsAboveTheConfiguredLimit() {
        RequestRateLimiter limiter = new RequestRateLimiter();

        assertThatCode(() -> limiter.check("login", "127.0.0.1", 2, Duration.ofMinutes(1), "limit"))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.check("login", "127.0.0.1", 2, Duration.ofMinutes(1), "limit"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.check("login", "127.0.0.1", 2, Duration.ofMinutes(1), "limit"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
    }
}
