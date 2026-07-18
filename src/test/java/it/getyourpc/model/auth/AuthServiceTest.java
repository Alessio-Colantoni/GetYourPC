package it.getyourpc.model.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    @Test
    void missingAccountUsesTheSameBcryptCostAsNewAccounts() {
        AuthService service = new AuthService(mock(AccountRepository.class));

        assertThat((String) ReflectionTestUtils.getField(service, "dummyPasswordHash"))
                .startsWith("$2a$12$");
    }

    private static final String PASSWORD1_HASH =
            "$2y$10$yIR790UTQfEMLAs9.qmPDuFJ1y.6eD9qxrvjkUwLhWwvT9tHk23u.";

    @Test
    void authenticatesAValidBcryptPassword() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findActiveByEmail("mario@example.com")).thenReturn(Optional.of(
                new AccountRepository.AccountRecord(1, "Mario", "Rossi", "user",
                        "mario@example.com", null, PASSWORD1_HASH)));

        AuthenticatedUser user = new AuthService(repository)
                .authenticate(new LoginRequest("mario@example.com", "password1"));

        assertThat(user.email()).isEqualTo("mario@example.com");
        assertThat(user.id()).isEqualTo(1);
    }

    @Test
    void authenticationCarriesThePasswordHashFingerprintIntoTheSession() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findActiveByEmail("mario@example.com")).thenReturn(Optional.of(
                new AccountRepository.AccountRecord(1, "Mario", "Rossi", "user",
                        "mario@example.com", null, PASSWORD1_HASH)));

        AuthService.AuthenticatedSession authenticated = new AuthService(repository)
                .authenticateSession(new LoginRequest("mario@example.com", "password1"));

        assertThat(authenticated.credentialFingerprint())
                .hasSize(64)
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(PASSWORD1_HASH);
        assertThat(authenticated.user().email()).isEqualTo("mario@example.com");
    }

    @Test
    void rejectsAnInvalidPassword() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findActiveByEmail("mario@example.com")).thenReturn(Optional.of(
                new AccountRepository.AccountRecord(1, "Mario", "Rossi", "user",
                        "mario@example.com", null, PASSWORD1_HASH)));

        assertThatThrownBy(() -> new AuthService(repository)
                .authenticate(new LoginRequest("mario@example.com", "wrong")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void rejectsUnknownOrInactiveAccounts() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findActiveByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new AuthService(repository)
                .authenticate(new LoginRequest("missing@example.com", "password")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
    }

    @Test
    void rejectsPasswordsBeyondTheBcryptByteLimit() {
        AccountRepository repository = mock(AccountRepository.class);

        assertThatThrownBy(() -> new AuthService(repository)
                .authenticate(new LoginRequest("mario@example.com", "è".repeat(40))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        verifyNoInteractions(repository);
    }

    @Test
    void reloadsTheActiveUserAndCurrentCredentialFingerprint() {
        AccountRepository repository = mock(AccountRepository.class);
        AuthenticatedUser current = new AuthenticatedUser(
                7, "Ada", "Lovelace", "user", "ada@example.com", null);
        when(repository.findActiveById(7)).thenReturn(Optional.of(new AccountRepository.AccountRecord(
                7, "Ada", "Lovelace", "user", "ada@example.com", null, PASSWORD1_HASH)));

        assertThat(new AuthService(repository).findActiveSession(7)).get().satisfies(session -> {
            assertThat(session.user()).isEqualTo(current);
            assertThat(session.credentialFingerprint()).isEqualTo(
                    AuthService.credentialFingerprint(PASSWORD1_HASH));
        });
    }
}
