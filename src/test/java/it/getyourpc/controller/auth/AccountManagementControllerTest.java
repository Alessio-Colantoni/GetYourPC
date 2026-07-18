package it.getyourpc.controller.auth;

import it.getyourpc.model.auth.*;
import it.getyourpc.model.common.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountManagementControllerTest {
    private static final String FINGERPRINT = "credential-fingerprint";
    @Test
    void registrationConfirmationCreatesAFreshAuthenticatedSession() {
        AccountVerificationService service = mock(AccountVerificationService.class);
        AuthService authService = mock(AuthService.class);
        AccountManagementController controller = controller(service, authService);
        AuthenticatedUser user = user();
        when(service.confirmRegistration(new RegisterConfirmRequest("ada@example.com", "12345")))
                .thenReturn(user);
        when(authService.findActiveSession(7))
                .thenReturn(Optional.of(new AuthService.AuthenticatedSession(user, FINGERPRINT)));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionUserGuard.SESSION_USER, "stale");

        assertThat(controller.confirmRegistration(
                new RegisterConfirmRequest("ada@example.com", "12345"), request)).isEqualTo(user);
        assertThat(request.getSession(false).getAttribute(SessionUserGuard.SESSION_USER)).isEqualTo(user);
        assertThat(request.getSession(false).getAttribute(
                SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT)).isEqualTo(FINGERPRINT);
    }

    @Test
    void authenticatedUserCanStartPasswordAndEmailChanges() {
        AccountVerificationService service = mock(AccountVerificationService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.findActiveSession(7)).thenReturn(Optional.of(authenticatedSession()));
        AccountManagementController controller = controller(service, authService);

        controller.startPasswordChange(new PasswordChangeStartRequest("password-attuale-sicura"),
                authenticatedRequest());
        controller.startEmailChange(new EmailChangeStartRequest(
                "password-attuale-sicura", "nuova@example.com"), authenticatedRequest());

        verify(service).startPasswordChange(user(),
                new PasswordChangeStartRequest("password-attuale-sicura"));
        verify(service).startEmailChange(user(),
                new EmailChangeStartRequest("password-attuale-sicura", "nuova@example.com"));
    }

    @Test
    void anonymousUserCannotStartAStandardPasswordChange() {
        AccountVerificationService service = mock(AccountVerificationService.class);
        AccountManagementController controller = controller(service, mock(AuthService.class));

        assertThatThrownBy(() -> controller.startPasswordChange(
                new PasswordChangeStartRequest("password-attuale-sicura"),
                new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        verifyNoInteractions(service);
    }

    @Test
    void successfulPasswordChangeInvalidatesTheCurrentSession() {
        AccountVerificationService service = mock(AccountVerificationService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.findActiveSession(7)).thenReturn(Optional.of(authenticatedSession()));
        AccountManagementController controller = controller(service, authService);
        MockHttpServletRequest request = authenticatedRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        controller.confirmPasswordChange(
                new PasswordChangeConfirmRequest("12345", "nuova-password-sicura"), request);

        assertThat(session.isInvalid()).isTrue();
        verify(service).confirmPasswordChange(user(),
                new PasswordChangeConfirmRequest("12345", "nuova-password-sicura"));
    }

    private static AccountManagementController controller(AccountVerificationService service,
                                                          AuthService authService) {
        return new AccountManagementController(service, mock(AccountProfileService.class),
                new RequestRateLimiter(), new SessionUserGuard(authService));
    }

    private static MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionUserGuard.SESSION_USER, user());
        request.getSession().setAttribute(
                SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT, FINGERPRINT);
        return request;
    }

    private static AuthService.AuthenticatedSession authenticatedSession() {
        return new AuthService.AuthenticatedSession(user(), FINGERPRINT);
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
    }
}
