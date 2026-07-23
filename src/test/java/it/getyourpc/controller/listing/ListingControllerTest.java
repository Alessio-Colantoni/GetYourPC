package it.getyourpc.controller.listing;

import it.getyourpc.controller.auth.AuthController;
import it.getyourpc.model.auth.AuthService;
import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.auth.SessionUserGuard;
import it.getyourpc.model.common.RequestRateLimiter;
import it.getyourpc.model.listing.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingControllerTest {
    private static final String FINGERPRINT = "credential-fingerprint";
    @Test
    void anonymousCreateDoesNotAllocateASession() {
        ListingController controller = new ListingController(
                mock(ListingService.class), guard(mock(AuthService.class)), new RequestRateLimiter());
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> controller.create(desktopRequest(), null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void inactiveSessionIsInvalidatedBeforeCreatingAListing() {
        ListingService listingService = mock(ListingService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.findActiveSession(7)).thenReturn(Optional.empty());
        ListingController controller = new ListingController(
                listingService, guard(authService), new RequestRateLimiter());
        MockHttpServletRequest request = authenticatedRequest();
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        assertThatThrownBy(() -> controller.create(desktopRequest(), null, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401");
        assertThat(session.isInvalid()).isTrue();
        verifyNoInteractions(listingService);
    }

    @Test
    void appliesIpAndUserRateLimitsBeforeCreatingAListing() {
        ListingService listingService = mock(ListingService.class);
        AuthService authService = mock(AuthService.class);
        RequestRateLimiter rateLimiter = mock(RequestRateLimiter.class);
        AuthenticatedUser current = user();
        when(authService.findActiveSession(7)).thenReturn(Optional.of(session(current)));
        ListingController controller = new ListingController(listingService, guard(authService), rateLimiter);
        MockHttpServletRequest request = authenticatedRequest();
        request.setRemoteAddr("203.0.113.10");

        controller.create(desktopRequest(), null, request);

        verify(rateLimiter).check(eq("listing-create-ip"), eq("203.0.113.10"), eq(20),
                eq(Duration.ofHours(1)), anyString());
        verify(rateLimiter).check(eq("listing-create-user"), eq("7"), eq(10),
                eq(Duration.ofHours(1)), anyString());
    }

    @Test
    void appliesAnIpRateLimitBeforeSearching() {
        RequestRateLimiter rateLimiter = mock(RequestRateLimiter.class);
        ListingController controller = new ListingController(
                mock(ListingService.class), guard(mock(AuthService.class)), rateLimiter);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.11");

        controller.search("desktop", BigDecimal.ZERO, BigDecimal.TEN,
                41.9, 12.5, 50, "", 50, request);

        verify(rateLimiter).check(eq("listing-search-ip"), eq("203.0.113.11"), eq(60),
                eq(Duration.ofMinutes(1)), anyString());
    }

    @Test
    void photosCannotBeRetainedByBrowserOrSharedCaches() {
        ListingService listingService = mock(ListingService.class);
        when(listingService.photo(42, 1))
                .thenReturn(new PhotoData(new byte[]{1, 2, 3}, "image/png"));
        ListingController controller = new ListingController(
                listingService, guard(mock(AuthService.class)), new RequestRateLimiter());

        var response = controller.photo(42, 1);

        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, no-store, max-age=0");
        assertThat(response.getHeaders().getFirst(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
    }

    @Test
    void reviewerCannotPublishOrManageListingsAsAUser() {
        ListingService listingService = mock(ListingService.class);
        AuthService authService = mock(AuthService.class);
        AuthenticatedUser reviewer = new AuthenticatedUser(
                7, "Ada", "Lovelace", "reviewer", "ada@example.com", null);
        when(authService.findActiveSession(7)).thenReturn(Optional.of(session(reviewer)));
        ListingController controller = new ListingController(
                listingService, guard(authService), new RequestRateLimiter());

        assertThatThrownBy(() -> controller.create(desktopRequest(), null, authenticatedRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThatThrownBy(() -> controller.mine(authenticatedRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThatThrownBy(() -> controller.deleteOwned(42, authenticatedRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verifyNoInteractions(listingService);
    }

    @Test
    void userCanListAndDeleteOnlyThroughTheirAuthenticatedIdentity() {
        ListingService listingService = mock(ListingService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.findActiveSession(7)).thenReturn(Optional.of(session(user())));
        ListingController controller = new ListingController(
                listingService, guard(authService), new RequestRateLimiter());

        controller.mine(authenticatedRequest());
        controller.deleteOwned(42, authenticatedRequest());

        verify(listingService).findOwnedBy(user());
        verify(listingService).deleteOwnedBy(user(), 42);
    }

    private static MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute(AuthController.SESSION_USER, user());
        request.getSession().setAttribute(
                SessionUserGuard.SESSION_CREDENTIAL_FINGERPRINT, FINGERPRINT);
        return request;
    }

    private static AuthService.AuthenticatedSession session(AuthenticatedUser user) {
        return new AuthService.AuthenticatedSession(user, FINGERPRINT);
    }

    private static AuthenticatedUser user() {
        return new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
    }

    private static SessionUserGuard guard(AuthService authService) {
        return new SessionUserGuard(authService);
    }

    private static ListingCreateRequest desktopRequest() {
        return new ListingCreateRequest("desktop", BigDecimal.TEN, "Italia", "Roma", null,
                null, null, null, "CPU", "Scheda madre", "GPU", "16 GB", "1 TB",
                "500 W", "Air", "ATX", false);
    }
}
