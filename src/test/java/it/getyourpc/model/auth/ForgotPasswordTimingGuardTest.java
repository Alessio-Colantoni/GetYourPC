package it.getyourpc.model.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForgotPasswordTimingGuardTest {
    @Test
    void enforcesTheConfiguredMinimumResponseDuration() {
        ForgotPasswordTimingGuard guard = new ForgotPasswordTimingGuard(20);
        long wallClockStarted = System.nanoTime();

        guard.awaitMinimumDuration(guard.start());

        assertThat(Duration.ofNanos(System.nanoTime() - wallClockStarted))
                .isGreaterThanOrEqualTo(Duration.ofMillis(15));
    }

    @Test
    void rejectsUnreasonableConfiguredDelays() {
        assertThatThrownBy(() -> new ForgotPasswordTimingGuard(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ForgotPasswordTimingGuard(5_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
