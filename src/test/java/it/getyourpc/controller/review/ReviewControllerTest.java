package it.getyourpc.controller.review;

import it.getyourpc.model.auth.AuthService;
import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.auth.SessionUserGuard;
import it.getyourpc.model.review.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReviewControllerTest {
    private static final String FINGERPRINT = "credential-fingerprint";
    private final ReviewService reviewService = mock(ReviewService.class);
    private final AuthService authService = mock(AuthService.class);
    private final ReviewController controller = new ReviewController(
            reviewService, new SessionUserGuard(authService));

    @Test
    void regularUsersCannotAccessReviewerEndpoints() {
        AuthenticatedUser user = user("user");
        when(authService.findActiveSession(7)).thenReturn(Optional.of(session(user)));

        assertThatThrownBy(() -> controller.activeListings(50, requestWith(user)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verifyNoInteractions(reviewService);
    }

    @Test
    void reviewerRoleIsRefreshedBeforeRemovingAListing() {
        AuthenticatedUser reviewer = user("reviewer");
        when(authService.findActiveSession(7)).thenReturn(Optional.of(session(reviewer)));

        controller.remove(42, true, requestWith(user("user")));

        verify(reviewService).remove(reviewer, 42, true);
    }

    private static MockHttpServletRequest requestWith(AuthenticatedUser user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(SessionUserGuard.SESSION_USER, user);
        request.getSession().setAttribute(
                SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT, FINGERPRINT);
        return request;
    }

    private static AuthService.AuthenticatedSession session(AuthenticatedUser user) {
        return new AuthService.AuthenticatedSession(user, FINGERPRINT);
    }

    private static AuthenticatedUser user(String role) {
        return new AuthenticatedUser(7, "Ada", "Lovelace", role, "ada@example.com", null);
    }
}
