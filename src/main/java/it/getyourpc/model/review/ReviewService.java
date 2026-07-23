package it.getyourpc.model.review;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.auth.SessionUserGuard;
import it.getyourpc.model.listing.ListingRepository;
import it.getyourpc.model.listing.ListingSummary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class ReviewService {
    private final ListingRepository listingRepository;

    public ReviewService(ListingRepository listingRepository) {
        this.listingRepository = listingRepository;
    }

    public List<ListingSummary> findActive(AuthenticatedUser reviewer, int limit) {
        requireReviewer(reviewer);
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite risultati non valido");
        }
        return listingRepository.findAllReportedActive(limit);
    }

    @Transactional
    public void remove(AuthenticatedUser reviewer, int listingId, boolean blockUser) {
        requireReviewer(reviewer);
        ListingRepository.ReviewTarget target = listingRepository.lockActiveReviewTarget(listingId)
                .orElseThrow(ReviewService::listingNotFound);
        if (blockUser && (target.sellerId() == reviewer.id()
                || !SessionUserGuard.USER_ROLE.equalsIgnoreCase(target.sellerRole()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Non è possibile bloccare un revisore");
        }
        if (!listingRepository.softRemove(listingId)) {
            throw listingNotFound();
        }
        if (blockUser) {
            if (!listingRepository.blockActiveUser(target.sellerId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "L'utente non può essere bloccato");
            }
            listingRepository.softRemoveAllActiveOwnedBy(target.sellerId());
        }
    }

    private static void requireReviewer(AuthenticatedUser user) {
        if (!SessionUserGuard.canReview(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accesso riservato ai revisori");
        }
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Annuncio non trovato");
    }
}
