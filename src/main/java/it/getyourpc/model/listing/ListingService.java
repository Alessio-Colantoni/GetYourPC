package it.getyourpc.model.listing;

import it.getyourpc.model.auth.AuthenticatedUser;
import it.getyourpc.model.auth.SessionUserGuard;
import it.getyourpc.model.geocoding.GeoPosition;
import it.getyourpc.model.geocoding.GeoapifyClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class ListingService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("100000.00");
    private final ListingRepository listingRepository;
    private final GeoapifyClient geoapifyClient;
    private final ImageSanitizer imageSanitizer;

    public ListingService(ListingRepository listingRepository, GeoapifyClient geoapifyClient,
                          ImageSanitizer imageSanitizer) {
        this.listingRepository = listingRepository;
        this.geoapifyClient = geoapifyClient;
        this.imageSanitizer = imageSanitizer;
    }

    public List<ListingSummary> search(String type, BigDecimal minPrice, BigDecimal maxPrice,
                                       double latitude, double longitude, double distanceKm,
                                       String keyword, int limit) {
        if (minPrice == null || maxPrice == null || minPrice.signum() < 0
                || maxPrice.compareTo(minPrice) < 0 || maxPrice.compareTo(MAX_PRICE) > 0
                || hasMoreThanTwoDecimals(minPrice) || hasMoreThanTwoDecimals(maxPrice)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intervallo di prezzo non valido");
        }
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordinate non valide");
        }
        if (!Double.isFinite(distanceKm) || distanceKm <= 0 || distanceKm > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Distanza non valida");
        }
        if (limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite risultati non valido");
        }
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        if (normalizedKeyword.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parola chiave troppo lunga");
        }
        return listingRepository.search(ListingType.from(type), minPrice, maxPrice,
                latitude, longitude, distanceKm, normalizedKeyword, limit);
    }

    public int create(AuthenticatedUser user, ListingCreateRequest request, List<MultipartFile> files) {
        requireUserRole(user);
        if (request.showPhone() && (user.phone() == null || user.phone().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aggiungi un numero di telefono al profilo prima di mostrarlo nell'annuncio");
        }
        ListingType type = ListingType.from(request.type());
        validateSpecificFields(type, request);
        List<byte[]> photos = readPhotos(files == null ? List.of() : files);
        GeoPosition position = geoapifyClient.geocodeLocation(
                request.country(), request.city(), request.address());
        return listingRepository.insert(user, request, position, photos, type);
    }

    public List<ListingSummary> findOwnedBy(AuthenticatedUser user) {
        requireUserRole(user);
        return listingRepository.findActiveOwnedBy(user.id());
    }

    public void deleteOwnedBy(AuthenticatedUser user, int listingId) {
        requireUserRole(user);
        if (!listingRepository.softDeleteOwned(listingId, user.id())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Annuncio non trovato");
        }
    }

    public void updateOwnedBy(AuthenticatedUser user, int listingId,
                              ListingCreateRequest request, List<MultipartFile> files) {
        requireUserRole(user);
        ListingType currentType = listingRepository.findTypeForOwned(listingId, user.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Annuncio non trovato"));
        ListingType requestedType = ListingType.from(request.type());
        if (currentType != requestedType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Non puoi cambiare il tipo dell'annuncio");
        }
        if (request.showPhone() && (user.phone() == null || user.phone().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aggiungi un numero di telefono al profilo prima di mostrarlo nell'annuncio");
        }
        validateSpecificFields(requestedType, request);
        List<byte[]> photos = readPhotos(files == null ? List.of() : files);
        GeoPosition position;
        try {
            position = geoapifyClient.geocodeLocation(
                    request.country(), request.city(), request.address());
        } catch (ResponseStatusException exception) {
            position = listingRepository.findPositionForOwned(listingId, user.id())
                    .orElseThrow(() -> exception);
        } catch (RuntimeException exception) {
            position = listingRepository.findPositionForOwned(listingId, user.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Servizio di geocodifica non disponibile", exception));
        }
        if (!listingRepository.updateGeneral(listingId, user.id(), request, position, photos)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Annuncio non trovato");
        }
        if (requestedType == ListingType.DESKTOP) {
            if (!listingRepository.updateDesktop(listingId, request)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Annuncio non trovato");
            }
        } else {
            if (!listingRepository.updateLaptop(listingId, request)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Annuncio non trovato");
            }
        }
    }

    public PhotoData photo(int listingId, int index) {
        return listingRepository.findPhoto(listingId, index)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Immagine non trovata"));
    }

    public void report(int listingId, AuthenticatedUser user) {
        Integer userId = user != null ? user.id() : null;
        listingRepository.insertReport(listingId, userId);
    }

    private static void validateSpecificFields(ListingType type, ListingCreateRequest request) {
        if (type == ListingType.LAPTOP && (blank(request.brand()) || blank(request.model())
                || request.screenSize() == null || request.screenSize().signum() <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Marca, modello e dimensione schermo sono obbligatori per un laptop");
        }
        if (type == ListingType.DESKTOP && (blank(request.motherboard()) || blank(request.power())
                || blank(request.cpuHeat()) || blank(request.pcCase()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Completa i dati specifici del desktop");
        }
    }

    private List<byte[]> readPhotos(List<MultipartFile> files) {
        if (files.size() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Puoi caricare al massimo tre immagini");
        }
        List<byte[]> photos = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            photos.add(imageSanitizer.sanitize(file).bytes());
        }
        return photos;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasMoreThanTwoDecimals(BigDecimal value) {
        return value.stripTrailingZeros().scale() > 2;
    }

    private static void requireUserRole(AuthenticatedUser user) {
        if (!SessionUserGuard.canSell(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Operazione riservata agli utenti venditori");
        }
    }
}
