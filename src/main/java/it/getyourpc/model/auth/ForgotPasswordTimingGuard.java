package it.getyourpc.model.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ForgotPasswordTimingGuard {
    private static final long MAXIMUM_DELAY_MILLISECONDS = 5_000;

    private final long minimumResponseNanos;

    public ForgotPasswordTimingGuard(
            @Value("${app.auth.forgot-password-minimum-response-ms:1000}") long minimumResponseMilliseconds) {
        if (minimumResponseMilliseconds < 0 || minimumResponseMilliseconds > MAXIMUM_DELAY_MILLISECONDS) {
            throw new IllegalArgumentException(
                    "FORGOT_PASSWORD_MINIMUM_RESPONSE_MS deve essere compreso tra 0 e 5000");
        }
        this.minimumResponseNanos = TimeUnit.MILLISECONDS.toNanos(minimumResponseMilliseconds);
    }

    long start() {
        return System.nanoTime();
    }

    void awaitMinimumDuration(long startedAtNanos) {
        long remaining = minimumResponseNanos - (System.nanoTime() - startedAtNanos);
        if (remaining <= 0) return;
        try {
            TimeUnit.NANOSECONDS.sleep(remaining);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
