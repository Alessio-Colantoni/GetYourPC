package it.getyourpc.controller.review;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.auth.SessionUserGuard;
import it.getyourpc.model.listing.ListingSummary;
import it.getyourpc.model.review.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reviewer/listings")
public class ReviewController {
    private final ReviewService reviewService;
    private final SessionUserGuard sessionUserGuard;

    public ReviewController(ReviewService reviewService, SessionUserGuard sessionUserGuard) {
        this.reviewService = reviewService;
        this.sessionUserGuard = sessionUserGuard;
    }

    @GetMapping
    public List<ListingSummary> activeListings(
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest httpRequest) {
        AuthenticatedUser reviewer = sessionUserGuard.requireAnyRole(
                httpRequest, SessionUserGuard.REVIEWER_ROLE, SessionUserGuard.ADMIN_ROLE);
        return reviewService.findActive(reviewer, limit);
    }

    @DeleteMapping("/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable int listingId,
            @RequestParam(defaultValue = "false") boolean blockUser,
            HttpServletRequest httpRequest) {
        AuthenticatedUser reviewer = sessionUserGuard.requireAnyRole(
                httpRequest, SessionUserGuard.REVIEWER_ROLE, SessionUserGuard.ADMIN_ROLE);
        reviewService.remove(reviewer, listingId, blockUser);
    }
}
