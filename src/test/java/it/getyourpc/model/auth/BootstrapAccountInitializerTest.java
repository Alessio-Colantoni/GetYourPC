package it.getyourpc.model.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAccountInitializerTest {
    private final AccountRepository repository = mock(AccountRepository.class);

    @Test
    void doesNothingWhenBootstrapIsDisabled() {
        BootstrapAccountInitializer initializer = new BootstrapAccountInitializer(
                repository, new MockEnvironment().withProperty("app.bootstrap-account.enabled", "false"));

        initializer.run(null);

        verify(repository, never()).lockEmail(org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).createActiveIfAbsent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void createsAnAccountWithNormalizedEmailAndBcryptPassword() {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("app.bootstrap-account.email", " Admin@Example.com ")
                .withProperty("app.bootstrap-account.password", "una-password-lunga-e-sicura")
                .withProperty("app.bootstrap-account.name", "Ada")
                .withProperty("app.bootstrap-account.surname", "Lovelace");
        when(repository.createActiveIfAbsent(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);

        new BootstrapAccountInitializer(repository, environment).run(null);

        BCryptPasswordEncoder verifier = new BCryptPasswordEncoder();
        verify(repository).lockEmail("admin@example.com");
        verify(repository).createActiveIfAbsent(
                eq("Ada"), eq("Lovelace"), eq("admin@example.com"),
                argThat(hash -> verifier.matches("una-password-lunga-e-sicura", hash)));
    }

    @Test
    void rejectsWeakBootstrapPassword() {
        MockEnvironment environment = enabledEnvironment()
                .withProperty("app.bootstrap-account.email", "admin@example.com")
                .withProperty("app.bootstrap-account.password", "corta")
                .withProperty("app.bootstrap-account.name", "Ada")
                .withProperty("app.bootstrap-account.surname", "Lovelace");

        assertThatThrownBy(() -> new BootstrapAccountInitializer(repository, environment).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("almeno 8 caratteri");
    }

    private MockEnvironment enabledEnvironment() {
        return new MockEnvironment().withProperty("app.bootstrap-account.enabled", "true");
    }
}
