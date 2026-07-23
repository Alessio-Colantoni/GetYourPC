package it.getyourpc.model.auth;

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

class SessionUserGuardTest {
    private static final String FINGERPRINT = "credential-fingerprint";
    private final AuthService authService = mock(AuthService.class);
    private final SessionUserGuard guard = new SessionUserGuard(authService);

    @Test
    void anonymousRequestsDoNotAllocateASession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> guard.requireAuthenticated(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void refreshesTheRoleFromTheDatabaseBeforeAuthorizing() {
        AuthenticatedUser stale = new AuthenticatedUser(
                7, "Ada", "Lovelace", "reviewer", "ada@example.com", null);
        AuthenticatedUser current = new AuthenticatedUser(
                7, "Ada", "Lovelace", "user", "ada@example.com", null);
        when(authService.findActiveSession(7)).thenReturn(Optional.of(session(current, FINGERPRINT)));
        MockHttpServletRequest request = requestWith(stale);

        assertThatThrownBy(() -> guard.requireRole(request, SessionUserGuard.REVIEWER_ROLE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(request.getSession(false).getAttribute(SessionUserGuard.SESSION_USER))
                .isEqualTo(current);
    }

    @Test
    void invalidatesTheSessionOfAnInactiveUser() {
        when(authService.findActiveSession(7)).thenReturn(Optional.empty());
        MockHttpServletRequest request = requestWith(user("user"));
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        assertThatThrownBy(() -> guard.requireAuthenticated(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void invalidatesSessionsCreatedBeforeTheLatestPasswordChange() {
        when(authService.findActiveSession(7))
                .thenReturn(Optional.of(session(user("user"), "new-fingerprint")));
        MockHttpServletRequest request = requestWith(user("user"));
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        assertThatThrownBy(() -> guard.requireAuthenticated(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void administratorsHaveBothSellerAndReviewerCapabilities() {
        AuthenticatedUser admin = user("admin");

        assertThat(SessionUserGuard.canSell(admin)).isTrue();
        assertThat(SessionUserGuard.canReview(admin)).isTrue();
        assertThat(SessionUserGuard.canReview(user("user"))).isFalse();
        assertThat(SessionUserGuard.canSell(user("reviewer"))).isFalse();
    }

    private static MockHttpServletRequest requestWith(AuthenticatedUser user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionUserGuard.SESSION_USER, user);
        request.getSession().setAttribute(SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT, FINGERPRINT);
        return request;
    }

    private static AuthService.AuthenticatedSession session(AuthenticatedUser user, String fingerprint) {
        return new AuthService.AuthenticatedSession(user, fingerprint);
    }

    private static AuthenticatedUser user(String role) {
        return new AuthenticatedUser(7, "Ada", "Lovelace", role, "ada@example.com", null);
    }
}
