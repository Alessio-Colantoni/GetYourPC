package it.getyourpc.controller.listing;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.auth.SessionUserGuard;
import it.getyourpc.model.common.RequestRateLimiter;
import it.getyourpc.model.listing.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/listings")
public class ListingController {
    private static final Duration SEARCH_WINDOW = Duration.ofMinutes(1);
    private static final Duration CREATE_WINDOW = Duration.ofHours(1);
    private final ListingService listingService;
    private final SessionUserGuard sessionUserGuard;
    private final RequestRateLimiter rateLimiter;

    public ListingController(ListingService listingService, SessionUserGuard sessionUserGuard,
                             RequestRateLimiter rateLimiter) {
        this.listingService = listingService;
        this.sessionUserGuard = sessionUserGuard;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public List<ListingSummary> search(
            @RequestParam String type,
            @RequestParam(defaultValue = "0") BigDecimal minPrice,
            @RequestParam(defaultValue = "100000") BigDecimal maxPrice,
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "50") double distanceKm,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest httpRequest) {
        rateLimiter.check("listing-search-ip", httpRequest.getRemoteAddr(), 60, SEARCH_WINDOW,
                "Troppe ricerche. Riprova tra un minuto");
        return listingService.search(type, minPrice, maxPrice, latitude, longitude,
                distanceKm, keyword, limit);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Integer>> create(
            @Valid @RequestPart("listing") ListingCreateRequest request,
            @RequestPart(value = "photos", required = false) List<MultipartFile> photos,
            HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAnyRole(httpRequest,
                SessionUserGuard.USER_ROLE, SessionUserGuard.ADMIN_ROLE);
        rateLimiter.check("listing-create-ip", httpRequest.getRemoteAddr(), 20, CREATE_WINDOW,
                "Troppi annunci pubblicati da questa rete. Riprova più tardi");
        rateLimiter.check("listing-create-user", Integer.toString(user.id()), 10, CREATE_WINDOW,
                "Hai pubblicato troppi annunci. Riprova più tardi");
        int id = listingService.create(user, request, photos);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @GetMapping("/mine")
    public List<ListingSummary> mine(HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAnyRole(httpRequest,
                SessionUserGuard.USER_ROLE, SessionUserGuard.ADMIN_ROLE);
        return listingService.findOwnedBy(user);
    }

    @DeleteMapping("/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOwned(@PathVariable int listingId, HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAnyRole(httpRequest,
                SessionUserGuard.USER_ROLE, SessionUserGuard.ADMIN_ROLE);
        listingService.deleteOwnedBy(user, listingId);
    }

    @RequestMapping(value = "/{listingId}", method = {RequestMethod.PATCH, RequestMethod.POST}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateOwned(@PathVariable int listingId,
                            @Valid @RequestPart("listing") ListingCreateRequest request,
                            @RequestPart(value = "photos", required = false) List<MultipartFile> photos,
                            HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.requireAnyRole(httpRequest,
                SessionUserGuard.USER_ROLE, SessionUserGuard.ADMIN_ROLE);
        listingService.updateOwnedBy(user, listingId, request, photos);
    }

    @GetMapping("/{listingId}/photos/{index}")
    public ResponseEntity<byte[]> photo(@PathVariable int listingId, @PathVariable int index) {
        PhotoData photo = listingService.photo(listingId, index);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(photo.contentType()))
                .body(photo.bytes());
    }

    @PostMapping("/{listingId}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@PathVariable int listingId, HttpServletRequest httpRequest) {
        AuthenticatedUser user = sessionUserGuard.optionalUser(httpRequest);
        listingService.report(listingId, user);
    }
}
