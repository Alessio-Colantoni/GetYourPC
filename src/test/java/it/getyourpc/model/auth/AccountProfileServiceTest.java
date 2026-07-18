package it.getyourpc.model.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AccountProfileServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountProfileService service = new AccountProfileService(accounts);
    private final AuthenticatedUser user = new AuthenticatedUser(
            7, "Ada", "Lovelace", "user", "ada@example.com", null);

    @Test
    void updatesPersonalDataAndReturnsTheFreshDatabaseUser() {
        AuthenticatedUser updated = new AuthenticatedUser(
                7, "Ada Maria", "Lovelace", "user", "ada@example.com", "+39 333 1234567");
        when(accounts.updateProfile(7, "Ada Maria", "Lovelace", "+39 333 1234567"))
                .thenReturn(true);
        when(accounts.findActiveUserById(7)).thenReturn(Optional.of(updated));

        assertThat(service.update(user,
                new ProfileUpdateRequest(" Ada Maria ", " Lovelace ", " +39 333 1234567 ")))
                .isEqualTo(updated);
    }

    @Test
    void deletesTheAccountOnlyWhenTheCurrentPasswordMatches() {
        String hash = new BCryptPasswordEncoder().encode("password-sicura");
        when(accounts.findActiveById(7)).thenReturn(Optional.of(new AccountRepository.AccountRecord(
                7, "Ada", "Lovelace", "user", "ada@example.com", null, hash)));
        when(accounts.deleteAccount(7)).thenReturn(true);

        service.delete(user, new DeleteAccountRequest("password-sicura"));

        verify(accounts).deleteAccount(7);
    }

    @Test
    void rejectsAnIncorrectPasswordWithoutDeletingAnything() {
        String hash = new BCryptPasswordEncoder().encode("password-sicura");
        when(accounts.findActiveById(7)).thenReturn(Optional.of(new AccountRepository.AccountRecord(
                7, "Ada", "Lovelace", "user", "ada@example.com", null, hash)));

        assertThatThrownBy(() -> service.delete(user, new DeleteAccountRequest("password-errata")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        verify(accounts).findActiveById(7);
        verifyNoMoreInteractions(accounts);
    }
}
