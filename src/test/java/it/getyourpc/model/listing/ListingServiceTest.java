package it.getyourpc.model.listing;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.geocoding.GeoPosition;
import it.getyourpc.model.geocoding.GeoapifyClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ListingServiceTest {
    private final ListingRepository repository = mock(ListingRepository.class);
    private final GeoapifyClient geoapify = mock(GeoapifyClient.class);
    private final ListingService service = new ListingService(repository, geoapify, new ImageSanitizer());

    @Test
    void rejectsNonFiniteOrOutOfRangeSearchValuesBeforeUsingTheDatabase() {
        assertThatThrownBy(() -> service.search("desktop", BigDecimal.ZERO, BigDecimal.TEN,
                Double.NaN, 12.5, 50, "", 50)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search("desktop", BigDecimal.ZERO, BigDecimal.TEN,
                91, 12.5, 50, "", 50)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search("desktop", BigDecimal.ZERO, BigDecimal.TEN,
                41.9, 12.5, Double.NaN, "", 50)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search("desktop", BigDecimal.ZERO, BigDecimal.TEN,
                41.9, 12.5, 50, "", 101)).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsPricesThatDoNotFitTheDatabasePrecision() {
        assertThatThrownBy(() -> service.search("desktop", new BigDecimal("0.001"), BigDecimal.TEN,
                41.9, 12.5, 50, "", 50)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.search("desktop", BigDecimal.ZERO,
                new BigDecimal("100000.01"), 41.9, 12.5, 50, "", 50))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sanitizesPhotosAndPersistsOnlyAfterGeocoding() throws Exception {
        AuthenticatedUser user = new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
        ListingCreateRequest request = desktopRequest();
        GeoPosition position = new GeoPosition("Roma, Italia", 41.9, 12.5);
        when(geoapify.geocodeLocation("Italia", "Roma", null)).thenReturn(position);
        when(repository.insert(eq(user), eq(request), eq(position),
                org.mockito.ArgumentMatchers.anyList(), eq(ListingType.DESKTOP))).thenReturn(42);

        int id = service.create(user, request, List.of(new MockMultipartFile(
                "photos", "computer.png", "text/html", png())));

        assertThat(id).isEqualTo(42);
        verify(repository).insert(eq(user), eq(request), eq(position),
                argThat(photos -> photos.size() == 1 && photos.get(0).length > 8
                        && photos.get(0)[0] == (byte) 0x89 && photos.get(0)[1] == 'P'),
                eq(ListingType.DESKTOP));
    }

    @Test
    void doesNotPersistAnythingWhenGeoapifyFails() {
        AuthenticatedUser user = new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
        ListingCreateRequest request = desktopRequest();
        when(geoapify.geocodeLocation("Italia", "Roma", null))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Servizio di geocodifica non disponibile"));

        assertThatThrownBy(() -> service.create(user, request, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");

        verify(geoapify).geocodeLocation("Italia", "Roma", null);
        verifyNoInteractions(repository);
    }

    @Test
    void updateUsesStoredLocationWhenGeocodingFails() {
        AuthenticatedUser user = new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
        ListingCreateRequest request = desktopRequest();
        when(repository.findTypeForOwned(42, 7)).thenReturn(Optional.of(ListingType.DESKTOP));
        when(geoapify.geocodeLocation("Italia", "Roma", null))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Servizio di geocodifica non disponibile"));
        GeoPosition existingPosition = new GeoPosition("Roma, Italia", 41.9, 12.5);
        when(repository.findPositionForOwned(42, 7)).thenReturn(Optional.of(existingPosition));
        when(repository.updateGeneral(eq(42), eq(7), eq(request), eq(existingPosition), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(true);
        when(repository.updateDesktop(eq(42), eq(request))).thenReturn(true);

        service.updateOwnedBy(user, 42, request, List.of());

        verify(repository).findPositionForOwned(42, 7);
        verify(repository).updateGeneral(eq(42), eq(7), eq(request), eq(existingPosition), org.mockito.ArgumentMatchers.anyList());
        verify(repository).updateDesktop(42, request);
    }

    @Test
    void listsOnlyTheCurrentUsersActiveListings() {
        AuthenticatedUser user = new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
        when(repository.findActiveOwnedBy(7)).thenReturn(List.of());

        assertThat(service.findOwnedBy(user)).isEmpty();

        verify(repository).findActiveOwnedBy(7);
    }

    @Test
    void returnsNotFoundWhenDeletingAMissingOrForeignListing() {
        AuthenticatedUser user = new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
        when(repository.softDeleteOwned(42, 7)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteOwnedBy(user, 42))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void softDeletesAListingOwnedByTheCurrentUser() {
        AuthenticatedUser user = new AuthenticatedUser(7, "Ada", "Lovelace", "user", "ada@example.com", null);
        when(repository.softDeleteOwned(42, 7)).thenReturn(true);

        service.deleteOwnedBy(user, 42);

        verify(repository).softDeleteOwned(42, 7);
    }

    @Test
    void reviewerCannotUseSellerOperationsThroughTheService() {
        AuthenticatedUser reviewer = new AuthenticatedUser(
                9, "Rita", "Reviewer", "reviewer", "rita@example.com", null);

        assertThatThrownBy(() -> service.create(reviewer, desktopRequest(), List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThatThrownBy(() -> service.findOwnedBy(reviewer))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        assertThatThrownBy(() -> service.deleteOwnedBy(reviewer, 42))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        verifyNoInteractions(repository, geoapify);
    }

    @Test
    void administratorCanUseSellerOperations() {
        AuthenticatedUser admin = new AuthenticatedUser(
                1, "Ada", "Admin", "admin", "admin@example.com", null);
        when(repository.findActiveOwnedBy(1)).thenReturn(List.of());

        assertThat(service.findOwnedBy(admin)).isEmpty();

        verify(repository).findActiveOwnedBy(1);
    }

    @Test
    void rejectsPhoneVisibilityUntilTheProfileHasAPhoneNumber() {
        AuthenticatedUser user = new AuthenticatedUser(
                7, "Ada", "Lovelace", "user", "ada@example.com", null);
        ListingCreateRequest request = desktopRequest(true);

        assertThatThrownBy(() -> service.create(user, request, List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");

        verifyNoInteractions(repository, geoapify);
    }

    private static ListingCreateRequest desktopRequest() {
        return desktopRequest(false);
    }

    private static ListingCreateRequest desktopRequest(boolean showPhone) {
        return new ListingCreateRequest("desktop", new BigDecimal("799.99"), "Italia", "Roma", null,
                null, null, null, "Ryzen 7", "B650", "RTX 4070", "32 GB", "1 TB",
                "750 W", "Air", "ATX", showPhone);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
