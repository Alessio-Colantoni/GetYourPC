package it.getyourpc.model.common;

import it.getyourpc.model.auth.VerificationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class DatabaseMaintenance {
    private final VerificationRepository verificationRepository;
    private final RateLimitRepository rateLimitRepository;

    public DatabaseMaintenance(VerificationRepository verificationRepository,
                               RateLimitRepository rateLimitRepository) {
        this.verificationRepository = verificationRepository;
        this.rateLimitRepository = rateLimitRepository;
    }

    @Scheduled(cron = "${app.maintenance.cleanup-cron}")
    public void deleteExpiredTemporaryData() {
        verificationRepository.deleteExpired();
        rateLimitRepository.deleteOlderThan(Instant.now().minus(24, ChronoUnit.HOURS));
    }
}
