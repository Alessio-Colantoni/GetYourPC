package it.getyourpc.model.auth;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewerProvisioningServiceTest {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ReviewerProvisioningService service = new ReviewerProvisioningService(accounts);

    @Test
    void locksTheEmailBeforeCreatingTheReviewer() {
        AuthenticatedUser reviewer = new AuthenticatedUser(
                12, "Rita", "Reviewer", "reviewer", "rita@example.com", null);
        when(accounts.existsByEmail("rita@example.com")).thenReturn(false);
        when(accounts.createActiveWithRole("Rita", "Reviewer", "rita@example.com",
                "hash", SessionUserGuard.REVIEWER_ROLE)).thenReturn(12);
        when(accounts.findActiveUserById(12)).thenReturn(Optional.of(reviewer));

        assertThat(service.create("Rita", "Reviewer", "rita@example.com", "hash"))
                .isEqualTo(reviewer);

        var order = inOrder(accounts);
        order.verify(accounts).lockEmail("rita@example.com");
        order.verify(accounts).existsByEmail("rita@example.com");
        order.verify(accounts).createActiveWithRole("Rita", "Reviewer", "rita@example.com",
                "hash", SessionUserGuard.REVIEWER_ROLE);
    }

    @Test
    void rejectsAnEmailAlreadyInUseWhileHoldingItsLock() {
        when(accounts.existsByEmail("rita@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                "Rita", "Reviewer", "rita@example.com", "hash"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        verify(accounts).lockEmail("rita@example.com");
        verify(accounts, never()).createActiveWithRole(
                "Rita", "Reviewer", "rita@example.com", "hash", SessionUserGuard.REVIEWER_ROLE);
    }

    @Test
    void reportsAFailedCompensationInsteadOfLeavingItSilent() {
        when(accounts.deleteReviewer(12)).thenReturn(false);

        assertThatThrownBy(() -> service.discardUndelivered(12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("annullare il reviewer");
    }
}
