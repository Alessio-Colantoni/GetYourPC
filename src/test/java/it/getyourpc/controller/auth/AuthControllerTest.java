package it.getyourpc.controller.auth;

import it.getyourpc.model.auth.*;
import it.getyourpc.model.common.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    private static final String FINGERPRINT = "credential-fingerprint";
    @Test
    void anonymousMeDoesNotCreateASession() {
        AuthService authService = mock(AuthService.class);
        AuthController controller = controller(authService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.me(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void anonymousLogoutDoesNotCreateASession() {
        AuthController controller = controller(mock(AuthService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();

        controller.logout(request);

        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void loginStoresTheCredentialFingerprintInTheNewSession() {
        AuthService authService = mock(AuthService.class);
        AuthenticatedUser user = new AuthenticatedUser(
                7, "Ada", "Lovelace", "user", "ada@example.com", null);
        LoginRequest credentials = new LoginRequest("ada@example.com", "password-sicura");
        when(authService.authenticateSession(credentials))
                .thenReturn(new AuthService.AuthenticatedSession(user, FINGERPRINT));
        AuthController controller = controller(authService);
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(controller.login(credentials, request)).isEqualTo(user);
        assertThat(request.getSession(false).getAttribute(SessionUserGuard.SESSION_USER)).isEqualTo(user);
        assertThat(request.getSession(false).getAttribute(
                SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT)).isEqualTo(FINGERPRINT);
    }

    @Test
    void meRefreshesTheSessionUserFromTheDatabase() {
        AuthService authService = mock(AuthService.class);
        AuthenticatedUser stale = new AuthenticatedUser(7, "Vecchio", "Nome", "user", "old@example.com", null);
        AuthenticatedUser current = new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
        when(authService.findActiveSession(7))
                .thenReturn(Optional.of(new AuthService.AuthenticatedSession(current, FINGERPRINT)));
        AuthController controller = controller(authService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(AuthController.SESSION_USER, stale);
        request.getSession().setAttribute(
                SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT, FINGERPRINT);

        assertThat(controller.me(request)).isEqualTo(current);
        assertThat(request.getSession(false).getAttribute(AuthController.SESSION_USER)).isEqualTo(current);
    }

    @Test
    void meInvalidatesTheSessionWhenTheDatabaseUserIsInactive() {
        AuthService authService = mock(AuthService.class);
        when(authService.findActiveSession(7)).thenReturn(Optional.empty());
        AuthController controller = controller(authService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = (MockHttpSession) request.getSession();
        session.setAttribute(AuthController.SESSION_USER,
                new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null));

        assertThatThrownBy(() -> controller.me(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        assertThat(session.isInvalid()).isTrue();
    }

    private static AuthController controller(AuthService authService) {
        return new AuthController(authService, new RequestRateLimiter(),
                new SessionUserGuard(authService));
    }
}
