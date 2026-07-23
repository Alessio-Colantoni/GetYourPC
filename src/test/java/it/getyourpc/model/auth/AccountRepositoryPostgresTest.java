package it.getyourpc.model.auth;

import it.getyourpc.model.common.RateLimitRepository;
import it.getyourpc.model.common.RequestRateLimiter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AccountRepositoryPostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbcClient;
    private static AccountRepository accounts;
    private static VerificationRepository verifications;
    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db.sql"));
        }
        jdbcClient = JdbcClient.create(dataSource);
        accounts = new AccountRepository(jdbcClient);
        verifications = new VerificationRepository(jdbcClient);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("""
                        TRUNCATE TABLE SPRING_SESSION_ATTRIBUTES, SPRING_SESSION,
                            RequestRateLimit, AccountVerification, Users
                        RESTART IDENTITY CASCADE
                        """).update();
    }

    @Test
    void accountCreationAndCredentialUpdatesWork() {
        int userId = accounts.createActive("Ada", "Lovelace", "ada@example.com", "old-hash");

        assertThat(accounts.existsByEmail("ADA@example.com")).isTrue();
        assertThat(accounts.findActiveById(userId)).get().extracting(AccountRepository.AccountRecord::email)
                .isEqualTo("ada@example.com");
        assertThat(accounts.updatePassword(userId, "new-hash")).isTrue();
        assertThat(accounts.updateEmail(userId, "new@example.com")).isTrue();
        assertThat(accounts.findActiveById(userId)).get().satisfies(account -> {
            assertThat(account.email()).isEqualTo("new@example.com");
            assertThat(account.passwordHash()).isEqualTo("new-hash");
        });
    }

    @Test
    void reviewerProvisioningUsesThePostgresEmailLock() {
        ReviewerProvisioningService provisioning = new ReviewerProvisioningService(accounts);
        AuthenticatedUser reviewer = new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> provisioning.create(
                        "Rita", "Reviewer", "rita@example.com", "password-hash"));

        assertThat(reviewer).isNotNull();
        assertThat(reviewer.role()).isEqualTo(SessionUserGuard.REVIEWER_ROLE);
        assertThat(accounts.existsByEmail("RITA@example.com")).isTrue();
    }

    @Test
    void profileRoleAndAccountDeletionWorkWithDependentData() {
        int userId = accounts.createActiveWithRole(
                "Rita", "Reviewer", "rita@example.com", "hash", "reviewer");
        assertThat(accounts.updateProfile(userId, "Rita Maria", "Reviewer", "+39 333 1234567"))
                .isTrue();
        assertThat(accounts.findActiveById(userId)).get().satisfies(account -> {
            assertThat(account.role()).isEqualTo("reviewer");
            assertThat(account.name()).isEqualTo("Rita Maria");
            assertThat(account.phone()).isEqualTo("+39 333 1234567");
        });
        int listingId = jdbcClient.sql("""
                        INSERT INTO PostGeneralInfo
                            (id_user, price, complete_address, latitude, longitude, show_phone, status)
                        VALUES (:userId, 500, 'Roma, Italia', 41.9, 12.5, TRUE, 'active')
                        RETURNING id_post
                        """).param("userId", userId).query(Integer.class).single();
        jdbcClient.sql("""
                        INSERT INTO Desktop
                            (id_post, cpu, motherboard, gpu, ram, memory, power, cpu_heat, pc_case)
                        VALUES (:listingId, 'CPU', 'MB', 'GPU', 'RAM', 'SSD', 'PSU', 'Air', 'ATX')
                        """).param("listingId", listingId).update();

        assertThat(accounts.deleteAccount(userId)).isTrue();

        assertThat(accounts.findActiveById(userId)).isEmpty();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM PostGeneralInfo WHERE id_user = :userId")
                .param("userId", userId).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM Desktop WHERE id_post = :listingId")
                .param("listingId", listingId).query(Integer.class).single()).isZero();
    }

    @Test
    void accountDeletionRollsBackAsOneAtomicOperation() {
        int userId = accounts.createActive("Ada", "Lovelace", "ada@example.com", "hash");
        int listingId = jdbcClient.sql("""
                        INSERT INTO PostGeneralInfo
                            (id_user, price, complete_address, latitude, longitude, status)
                        VALUES (:userId, 500, 'Roma, Italia', 41.9, 12.5, 'active')
                        RETURNING id_post
                        """).param("userId", userId).query(Integer.class).single();
        jdbcClient.sql("""
                        INSERT INTO Laptop
                            (id_post, brand, model, screen_size, cpu, gpu, ram, memory)
                        VALUES (:listingId, 'Brand', 'Model', 15.6, 'CPU', 'GPU', 'RAM', 'SSD')
                        """).param("listingId", listingId).update();

        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> {
                    assertThat(accounts.deleteAccount(userId)).isTrue();
                    status.setRollbackOnly();
                });

        assertThat(accounts.findActiveById(userId)).isPresent();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM PostGeneralInfo WHERE id_post = :listingId")
                .param("listingId", listingId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM Laptop WHERE id_post = :listingId")
                .param("listingId", listingId).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void verificationReplacementAttemptsAndDeletionWork() {
        int userId = accounts.createActive("Ada", "Lovelace", "ada@example.com", "hash");
        VerificationRepository.VerificationDraft first = new VerificationRepository.VerificationDraft(
                AccountVerificationService.CHANGE_EMAIL, userId, "first@example.com",
                null, null, null, "code-hash-1", Instant.now().plusSeconds(600));
        VerificationRepository.VerificationDraft second = new VerificationRepository.VerificationDraft(
                AccountVerificationService.CHANGE_EMAIL, userId, "second@example.com",
                null, null, null, "code-hash-2", Instant.now().plusSeconds(600));

        verifications.replace(first);
        verifications.replace(second);
        VerificationRepository.VerificationRecord stored = verifications.findForUserForUpdate(
                AccountVerificationService.CHANGE_EMAIL, userId).orElseThrow();
        assertThat(stored.email()).isEqualTo("second@example.com");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM AccountVerification")
                .query(Integer.class).single()).isEqualTo(1);

        verifications.incrementAttempts(stored.id());
        assertThat(verifications.findForUserForUpdate(
                AccountVerificationService.CHANGE_EMAIL, userId).orElseThrow().attempts()).isEqualTo(1);
        verifications.delete(stored.id());
        assertThat(verifications.findForUserForUpdate(
                AccountVerificationService.CHANGE_EMAIL, userId)).isEmpty();
    }

    @Test
    void concurrentVerificationStartsKeepExactlyOneUsableRow() throws Exception {
        runConcurrently(12, index -> verifications.replace(
                new VerificationRepository.VerificationDraft(
                        AccountVerificationService.REGISTER, null, "ada@example.com",
                        "Ada", "Lovelace", "password-hash-" + index, "code-hash-" + index,
                        Instant.now().plusSeconds(600))));

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM AccountVerification
                        WHERE purpose = 'register' AND LOWER(email) = 'ada@example.com'
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(verifications.findForEmailForUpdate(
                AccountVerificationService.REGISTER, "ada@example.com")).isPresent();
    }

    @Test
    void expiredVerificationsAreRemovedByMaintenanceCleanup() {
        verifications.replace(new VerificationRepository.VerificationDraft(
                AccountVerificationService.REGISTER, null, "expired@example.com",
                "Ada", "Lovelace", "password-hash", "code-hash",
                Instant.now().minusSeconds(1)));

        assertThat(verifications.deleteExpired()).isEqualTo(1);
        assertThat(verifications.findForEmailForUpdate(
                AccountVerificationService.REGISTER, "expired@example.com")).isEmpty();
    }

    @Test
    void schemaUpgradeDeduplicatesLegacyVerificationsBeforeAddingUniqueIndexes() throws Exception {
        jdbcClient.sql("DROP INDEX IF EXISTS uk_verification_user").update();
        jdbcClient.sql("DROP INDEX IF EXISTS uk_verification_email").update();
        jdbcClient.sql("""
                        INSERT INTO AccountVerification
                            (purpose, email, name, surname, password_hash, code_hash, expires_at)
                        VALUES
                            ('register', 'ADA@example.com', 'Ada', 'Lovelace', 'password-1',
                             'code-1', CURRENT_TIMESTAMP + INTERVAL '10 minutes'),
                            ('register', 'ada@example.com', 'Ada', 'Lovelace', 'password-2',
                             'code-2', CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                        """).update();

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db.sql"));
        }

        VerificationRepository.VerificationRecord remaining = verifications.findForEmailForUpdate(
                AccountVerificationService.REGISTER, "ada@example.com").orElseThrow();
        assertThat(remaining.codeHash()).isEqualTo("code-2");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM AccountVerification
                        WHERE purpose = 'register' AND LOWER(email) = 'ada@example.com'
                        """).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM pg_indexes
                        WHERE indexname IN ('uk_verification_email', 'uk_verification_user')
                          AND indexdef ILIKE 'CREATE UNIQUE INDEX%'
                        """).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void databaseRateLimitIsAtomicAcrossConcurrentRequests() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter(new RateLimitRepository(jdbcClient));
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(20, ignored -> {
            try {
                limiter.check("concurrent-test", "same-client", 5,
                        Duration.ofMinutes(1), "limit");
                accepted.incrementAndGet();
            } catch (org.springframework.web.server.ResponseStatusException exception) {
                assertThat(exception.getStatusCode().value()).isEqualTo(429);
                rejected.incrementAndGet();
            }
        });

        assertThat(accepted).hasValue(5);
        assertThat(rejected).hasValue(15);
        assertThat(jdbcClient.sql("""
                        SELECT requests FROM RequestRateLimit
                        WHERE scope = 'concurrent-test' AND client_id = 'same-client'
                """).query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void authenticatedUserRoundTripsThroughPostgresBackedSession() {
        JdbcIndexedSessionRepository sessions = new JdbcIndexedSessionRepository(
                new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        sessions.setCleanupCron("-");
        sessions.afterPropertiesSet();
        try {
            SessionRepository sessionStore = sessions;
            AuthenticatedUser expected = new AuthenticatedUser(
                    7, "Ada", "Lovelace", "user", "ada@example.com", "+39 333 1234567");
            Session session = (Session) sessionStore.createSession();
            session.setAttribute(SessionUserGuard.SESSION_USER, expected);
            session.setAttribute(SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT,
                    "credential-fingerprint");
            sessionStore.save(session);

            Session loaded = (Session) sessionStore.findById(session.getId());

            assertThat(loaded).isNotNull();
            assertThat((AuthenticatedUser) loaded.getAttribute(SessionUserGuard.SESSION_USER))
                    .isEqualTo(expected);
            assertThat((String) loaded.getAttribute(
                    SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT))
                    .isEqualTo("credential-fingerprint");
        } finally {
            sessions.destroy();
        }
    }

    @Test
    void passwordFingerprintRevokesSessionsCheckedByAnotherApplicationInstance() {
        int userId = accounts.createActive("Ada", "Lovelace", "ada@example.com", "old-hash");
        AuthenticatedUser user = accounts.findActiveUserById(userId).orElseThrow();
        AccountRepository repositoryOnAnotherInstance = new AccountRepository(JdbcClient.create(dataSource));
        SessionUserGuard guardOnAnotherInstance = new SessionUserGuard(
                new AuthService(repositoryOnAnotherInstance));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        SessionUserGuard.storeAuthenticatedUser(session, user,
                AuthService.credentialFingerprint("old-hash"));

        assertThat(guardOnAnotherInstance.requireAuthenticated(request)).isEqualTo(user);
        assertThat(accounts.updatePassword(userId, "new-hash")).isTrue();

        assertThatThrownBy(() -> guardOnAnotherInstance.requireAuthenticated(request))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401");
        assertThat(session.isInvalid()).isTrue();
    }

    private static void runConcurrently(int taskCount, IntConsumer task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < taskCount; index++) {
                int taskIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    task.accept(taskIndex);
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
