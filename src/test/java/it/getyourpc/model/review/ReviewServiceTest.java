package it.getyourpc.model.review;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.listing.ListingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReviewServiceTest {
    private final ListingRepository repository = mock(ListingRepository.class);
    private final ReviewService service = new ReviewService(repository);

    @Test
    void rejectsNonReviewerCallsEvenWhenTheCallerIsAuthenticated() {
        assertThatThrownBy(() -> service.findActive(user(7, "user"), 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                                ((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(repository);
    }

    @Test
    void validatesTheReviewListLimit() {
        assertThatThrownBy(() -> service.findActive(user(9, "reviewer"), 101))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        verifyNoInteractions(repository);
    }

    @Test
    void returnsActiveListingsToAReviewer() {
        when(repository.findAllReportedActive(50)).thenReturn(List.of());

        service.findActive(user(9, "reviewer"), 50);

        verify(repository).findAllReportedActive(50);
    }

    @Test
    void removesAListingWithoutBlockingItsSeller() {
        when(repository.lockActiveReviewTarget(42))
                .thenReturn(Optional.of(new ListingRepository.ReviewTarget(7, "user")));
        when(repository.softRemove(42)).thenReturn(true);

        service.remove(user(9, "reviewer"), 42, false);

        verify(repository).softRemove(42);
        verify(repository, never()).blockActiveUser(7);
        verify(repository, never()).softRemoveAllActiveOwnedBy(7);
    }

    @Test
    void blocksTheSellerAndRemovesAllTheirActiveListingsAtomically() {
        when(repository.lockActiveReviewTarget(42))
                .thenReturn(Optional.of(new ListingRepository.ReviewTarget(7, "user")));
        when(repository.softRemove(42)).thenReturn(true);
        when(repository.blockActiveUser(7)).thenReturn(true);

        service.remove(user(9, "reviewer"), 42, true);

        var order = inOrder(repository);
        order.verify(repository).softRemove(42);
        order.verify(repository).blockActiveUser(7);
        order.verify(repository).softRemoveAllActiveOwnedBy(7);
    }

    @Test
    void reportsMissingOrAlreadyInactiveListingsAsNotFound() {
        when(repository.lockActiveReviewTarget(42)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remove(user(9, "reviewer"), 42, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
        verify(repository, never()).softRemove(42);
    }

    @Test
    void cannotBlockItselfOrAnotherReviewer() {
        when(repository.lockActiveReviewTarget(42))
                .thenReturn(Optional.of(new ListingRepository.ReviewTarget(9, "user")));
        assertThatThrownBy(() -> service.remove(user(9, "reviewer"), 42, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        when(repository.lockActiveReviewTarget(43))
                .thenReturn(Optional.of(new ListingRepository.ReviewTarget(10, "reviewer")));
        assertThatThrownBy(() -> service.remove(user(9, "reviewer"), 43, true))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");

        verify(repository, never()).softRemove(42);
        verify(repository, never()).softRemove(43);
    }

    private static AuthenticatedUser user(int id, String role) {
        return new AuthenticatedUser(id, "Ada", "Lovelace", role, "ada@example.com", null);
    }
}
