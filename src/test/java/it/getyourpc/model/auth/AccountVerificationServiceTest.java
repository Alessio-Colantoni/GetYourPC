package it.getyourpc.model.auth;

import it.getyourpc.mail.MailjetClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountVerificationServiceTest {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    @Test
    void registrationStoresOnlyHashesAndEmailsAFiveDigitCode() {
        AccountRepository accounts = mock(AccountRepository.class);
        VerificationRepository verifications = mock(VerificationRepository.class);
        MailjetClient mailjet = mock(MailjetClient.class);
        AccountVerificationService service = new AccountVerificationService(accounts, verifications, mailjet);
        when(accounts.existsByEmail("ada@example.com")).thenReturn(false);

        VerificationStarted result = service.startRegistration(new RegisterStartRequest(
                " Ada ", " Lovelace ", " ADA@Example.com ", "una-password-lunga-e-sicura"));

        ArgumentCaptor<VerificationRepository.VerificationDraft> draft =
                ArgumentCaptor.forClass(VerificationRepository.VerificationDraft.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(verifications).replace(draft.capture());
        verify(mailjet).sendVerificationCode(org.mockito.ArgumentMatchers.eq("ada@example.com"),
                code.capture(), org.mockito.ArgumentMatchers.anyString());
        var order = inOrder(verifications, mailjet);
        order.verify(verifications).replace(org.mockito.ArgumentMatchers.any());
        order.verify(mailjet).sendVerificationCode(org.mockito.ArgumentMatchers.eq("ada@example.com"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertThat(code.getValue()).matches("\\d{5}");
        assertThat(new BCryptPasswordEncoder().matches(code.getValue(), draft.getValue().codeHash())).isTrue();
        assertThat(new BCryptPasswordEncoder().matches(
                "una-password-lunga-e-sicura", draft.getValue().passwordHash())).isTrue();
        assertThat(draft.getValue().name()).isEqualTo("Ada");
        assertThat(result).isEqualTo(new VerificationStarted("ada@example.com", 600));
    }

    @Test
    void registrationConfirmationCreatesTheUserAndReturnsIt() {
        AccountRepository accounts = mock(AccountRepository.class);
        VerificationRepository verifications = mock(VerificationRepository.class);
        AccountVerificationService service = new AccountVerificationService(
                accounts, verifications, mock(MailjetClient.class));
        VerificationRepository.VerificationRecord record = verification(
                AccountVerificationService.REGISTER, null, "ada@example.com", "12345", 0);
        when(verifications.findForEmailForUpdate(
                AccountVerificationService.REGISTER, "ada@example.com")).thenReturn(Optional.of(record));
        when(accounts.existsByEmail("ada@example.com")).thenReturn(false);
        when(accounts.createActive("Ada", "Lovelace", "ada@example.com", record.passwordHash()))
                .thenReturn(7);
        AuthenticatedUser expected = user("ada@example.com");
        when(accounts.findActiveUserById(7)).thenReturn(Optional.of(expected));

        assertThat(service.confirmRegistration(
                new RegisterConfirmRequest("ada@example.com", "12345"))).isEqualTo(expected);
        verify(accounts).lockEmail("ada@example.com");
        verify(verifications).delete(record.id());
    }

    @Test
    void invalidCodeConsumesAnAttemptWithoutChangingTheAccount() {
        AccountRepository accounts = mock(AccountRepository.class);
        VerificationRepository verifications = mock(VerificationRepository.class);
        AccountVerificationService service = new AccountVerificationService(
                accounts, verifications, mock(MailjetClient.class));
        VerificationRepository.VerificationRecord record = verification(
                AccountVerificationService.FORGOT_PASSWORD, 7, "ada@example.com", "12345", 0);
        when(verifications.findForEmailForUpdate(
                AccountVerificationService.FORGOT_PASSWORD, "ada@example.com"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.confirmForgotPassword(
                new ForgotPasswordConfirmRequest("ada@example.com", "99999",
                        "una-password-nuova-e-sicura")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(verifications).incrementAttempts(record.id());
        verify(accounts, never()).updatePassword(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void forgotPasswordDoesNotRevealOrEmailUnknownAccounts() {
        AccountRepository accounts = mock(AccountRepository.class);
        VerificationRepository verifications = mock(VerificationRepository.class);
        MailjetClient mailjet = mock(MailjetClient.class);
        when(accounts.findActiveByEmail("missing@example.com")).thenReturn(Optional.empty());

        VerificationStarted result = new AccountVerificationService(accounts, verifications, mailjet)
                .startForgotPassword(new ForgotPasswordStartRequest("missing@example.com"));

        assertThat(result.email()).isEqualTo("missing@example.com");
        assertThat(result.deliveryConfirmed()).isFalse();
        verifyNoInteractions(verifications, mailjet);
    }

    @Test
    void keepsTheCodeUsableWhenMailDeliveryCannotBeConfirmed() {
        AccountRepository accounts = mock(AccountRepository.class);
        VerificationRepository verifications = mock(VerificationRepository.class);
        MailjetClient mailjet = mock(MailjetClient.class);
        when(accounts.existsByEmail("ada@example.com")).thenReturn(false);
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mailjet non raggiungibile"))
                .when(mailjet).sendVerificationCode(
                        org.mockito.ArgumentMatchers.eq("ada@example.com"),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());

        VerificationStarted result = new AccountVerificationService(accounts, verifications, mailjet)
                .startRegistration(new RegisterStartRequest(
                        "Ada", "Lovelace", "ada@example.com", "una-password-lunga-e-sicura"));

        assertThat(result.deliveryConfirmed()).isFalse();
        verify(verifications).replace(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmationDeletesTheVerificationWhenItsAccountNoLongerExists() {
        AccountRepository accounts = mock(AccountRepository.class);
        VerificationRepository verifications = mock(VerificationRepository.class);
        AccountVerificationService service = new AccountVerificationService(
                accounts, verifications, mock(MailjetClient.class));
        VerificationRepository.VerificationRecord record = verification(
                AccountVerificationService.FORGOT_PASSWORD, 7, "ada@example.com", "12345", 0);
        when(verifications.findForEmailForUpdate(
                AccountVerificationService.FORGOT_PASSWORD, "ada@example.com"))
                .thenReturn(Optional.of(record));
        when(accounts.findActiveById(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmForgotPassword(
                new ForgotPasswordConfirmRequest("ada@example.com", "12345",
                        "una-password-nuova-e-sicura")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verify(verifications).delete(record.id());
    }

    @Test
    void emailChangeRequiresTheCurrentPasswordAndTargetsTheNewAddress() {
        AccountRepository accounts = mock(AccountRepository.class);
        VerificationRepository verifications = mock(VerificationRepository.class);
        MailjetClient mailjet = mock(MailjetClient.class);
        AccountVerificationService service = new AccountVerificationService(accounts, verifications, mailjet);
        String passwordHash = ENCODER.encode("password-attuale-sicura");
        when(accounts.findActiveById(7)).thenReturn(Optional.of(new AccountRepository.AccountRecord(
                7, "Ada", "Lovelace", "user", "ada@example.com", null, passwordHash)));
        when(accounts.existsByEmail("nuova@example.com")).thenReturn(false);

        service.startEmailChange(user("ada@example.com"),
                new EmailChangeStartRequest("password-attuale-sicura", "NUOVA@example.com"));

        ArgumentCaptor<VerificationRepository.VerificationDraft> draft =
                ArgumentCaptor.forClass(VerificationRepository.VerificationDraft.class);
        verify(verifications).replace(draft.capture());
        assertThat(draft.getValue().purpose()).isEqualTo(AccountVerificationService.CHANGE_EMAIL);
        assertThat(draft.getValue().email()).isEqualTo("nuova@example.com");
        verify(mailjet).sendVerificationCode(org.mockito.ArgumentMatchers.eq("nuova@example.com"),
                org.mockito.ArgumentMatchers.matches("\\d{5}"), org.mockito.ArgumentMatchers.anyString());
    }

    private static VerificationRepository.VerificationRecord verification(
            String purpose, Integer userId, String email, String code, int attempts) {
        return new VerificationRepository.VerificationRecord(
                42, purpose, userId, email, "Ada", "Lovelace",
                ENCODER.encode("una-password-lunga-e-sicura"), ENCODER.encode(code),
                Instant.now().plusSeconds(600), attempts);
    }

    private static AuthenticatedUser user(String email) {
        return new AuthenticatedUser(7, "Ada", "Lovelace", "user", email, null);
    }
}
