package it.getyourpc.model.geocoding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Locale;
import java.util.stream.Stream;

@Component
public class GeoapifyClient {
    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;
    private final Cache<String, GeoPosition> locations = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofHours(24))
            .build();

    public GeoapifyClient(RestClient.Builder restClientBuilder,
                          @Value("${geoapify.api-key:}") String apiKey,
                          @Value("${geoapify.base-url:https://api.geoapify.com}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = baseUrl == null ? "https://api.geoapify.com" : baseUrl.replaceAll("/+$", "");
    }

    public GeoPosition geocodeLocation(String country, String city, String address) {
        if (country == null || country.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inserisci il paese");
        }
        if (city == null || city.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Inserisci la città");
        }
        validateLength(country, 100, "Il paese è troppo lungo");
        validateLength(city, 100, "La città è troppo lunga");
        validateLength(address, 255, "L'indirizzo è troppo lungo");
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Geoapify non configurato");
        }
        String query = Stream.of(address, city, country)
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .reduce((first, second) -> first + ", " + second)
                .orElseThrow();
        String cacheKey = normalize(country) + '|' + normalize(city) + '|' + normalize(address);
        GeoPosition cached = locations.getIfPresent(cacheKey);
        if (cached != null) return cached;

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl + "/v1/geocode/search")
                .queryParam("text", query.trim())
                .queryParam("format", "json")
                .queryParam("limit", 5)
                .queryParam("lang", "it")
                .queryParam("bias", "countrycode:none")
                .queryParam("apiKey", apiKey);
        if (address == null || address.isBlank()) {
            uriBuilder.queryParam("type", "city");
        }
        URI uri = uriBuilder.build().encode().toUri();
        try {
            JsonNode response = restClient.get().uri(uri).retrieve().body(JsonNode.class);
            JsonNode results = response == null ? null : response.path("results");
            if (results == null || !results.isArray()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Risposta di geocodifica non valida");
            }
            if (results.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Località non trovata");
            }
            JsonNode first = null;
            boolean hasObjectResult = false;
            for (JsonNode result : results) {
                if (!result.isObject()) continue;
                hasObjectResult = true;
                if (matchesRequiredComponents(result, country, city)) {
                    first = result;
                    break;
                }
            }
            if (!hasObjectResult) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Risposta di geocodifica non valida");
            }
            if (first == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Paese e città non corrispondono a una località verificata");
            }
            JsonNode latitudeNode = first.path("lat");
            JsonNode longitudeNode = first.path("lon");
            if (!latitudeNode.isNumber() || !longitudeNode.isNumber()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Risposta di geocodifica incompleta");
            }
            double latitude = latitudeNode.asDouble();
            double longitude = longitudeNode.asDouble();
            if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                    || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Coordinate di geocodifica non valide");
            }
            String formattedAddress = first.path("formatted").asText();
            if (formattedAddress.isBlank()) formattedAddress = query;
            GeoPosition position = new GeoPosition(formattedAddress, latitude, longitude);
            locations.put(cacheKey, position);
            return position;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Servizio di geocodifica non disponibile");
        }
    }

    private static void validateLength(String value, int maximum, String message) {
        if (value != null && value.length() > maximum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
    }

    static void validateRequiredComponents(JsonNode result, String requestedCountry, String requestedCity) {
        if (!matchesRequiredComponents(result, requestedCountry, requestedCity)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Paese e città non corrispondono a una località verificata");
        }
    }

    private static boolean matchesRequiredComponents(JsonNode result, String requestedCountry,
                                                      String requestedCity) {
        String actualCountry = result.path("country").asText();
        String actualCity = firstPresent(result, "city", "town", "village", "municipality");
        return samePlace(requestedCountry, actualCountry) && samePlace(requestedCity, actualCity);
    }

    private static String firstPresent(JsonNode result, String... fields) {
        for (String field : fields) {
            String value = result.path(field).asText();
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean samePlace(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String withoutAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return withoutAccents.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim().replaceAll("\\s+", " ");
    }
}
