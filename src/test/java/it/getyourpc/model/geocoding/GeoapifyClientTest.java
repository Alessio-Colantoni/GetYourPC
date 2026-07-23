package it.getyourpc.model.geocoding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoapifyClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsTheExpectedContractAndParsesTheFirstMatchingResult() throws Exception {
        try (GeoapifyServer server = serve("""
                {"results":[
                  {"country":"Italia","city":"Milano","lat":45.4642,"lon":9.1900,"formatted":"Milano, Italia"},
                  {"country":"Italia","city":"Forlì","lat":44.2226,"lon":12.0408,"formatted":"Via dell'Unità, Forlì, Italia"}
                ]}
                """)) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            GeoPosition position = client.geocodeLocation(
                    "Italia", "Forlì", "Via dell'Unità 10");

            assertThat(position.formattedAddress()).isEqualTo("Via dell'Unità, Forlì, Italia");
            assertThat(position.latitude()).isEqualTo(44.2226);
            assertThat(position.longitude()).isEqualTo(12.0408);
            assertThat(server.decodedQuery())
                    .contains("text=Via dell'Unità 10, Forlì, Italia")
                    .contains("format=json")
                    .contains("limit=5")
                    .contains("lang=it")
                    .contains("bias=countrycode:none")
                    .contains("apiKey=test-key")
                    .doesNotContain("type=city");
        }
    }

    @Test
    void requestsACityResultWhenTheOptionalAddressIsMissing() throws Exception {
        try (GeoapifyServer server = serve("""
                {"results":[{"country":"Italia","town":"Frascati","lat":41.806,"lon":12.681,
                  "formatted":"Frascati, Roma Capitale, Italia"}]}
                """)) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            GeoPosition position = client.geocodeLocation("Italia", "Frascati", null);

            assertThat(position.formattedAddress()).startsWith("Frascati");
            assertThat(server.decodedQuery())
                    .contains("text=Frascati, Italia")
                    .contains("type=city");
        }
    }

    @Test
    void mapsEmptyAndMalformedGeoapifyResponsesToExplicitErrors() throws Exception {
        try (GeoapifyServer server = serve("{\"results\":[]}")) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            assertThatThrownBy(() -> client.geocodeLocation("Italia", "Roma", null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }

        try (GeoapifyServer server = serve("""
                {"results":[{"country":"Italia","city":"Roma","lat":"41.9","lon":12.5}]}
                """)) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            assertThatThrownBy(() -> client.geocodeLocation("Italia", "Roma", null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("502");
        }
    }

    @Test
    void distinguishesMalformedResponsesFromMissingAndMismatchedLocations() throws Exception {
        try (GeoapifyServer server = serve("{}")) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            assertThatThrownBy(() -> client.geocodeLocation("Italia", "Roma", null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(exception -> assertThat(
                            ((ResponseStatusException) exception).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_GATEWAY));
        }

        try (GeoapifyServer server = serve("{\"results\":[true]}")) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            assertThatThrownBy(() -> client.geocodeLocation("Italia", "Roma", null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(exception -> assertThat(
                            ((ResponseStatusException) exception).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_GATEWAY));
        }

        try (GeoapifyServer server = serve("""
                {"results":[{"country":"Italia","city":"Milano","lat":45.46,"lon":9.19}]}
                """)) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            assertThatThrownBy(() -> client.geocodeLocation("Italia", "Roma", null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(exception -> assertThat(
                            ((ResponseStatusException) exception).getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    @Test
    void cachesEquivalentNormalizedRequestsOnlyAfterSuccessfulValidation() throws Exception {
        try (GeoapifyServer server = serve("""
                {"results":[{"country":"Italia","city":"Roma","lat":41.9,"lon":12.5,
                  "formatted":"Roma, Italia"}]}
                """)) {
            GeoapifyClient client = new GeoapifyClient(
                    RestClient.builder(), "test-key", server.baseUrl());

            GeoPosition first = client.geocodeLocation(" Italia ", "Roma", null);
            GeoPosition second = client.geocodeLocation("italia", "ROMA", "");

            assertThat(second).isEqualTo(first);
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void acceptsMatchingCountryAndCity() throws Exception {
        JsonNode result = result("Italia", "Roma");

        assertThatCode(() -> GeoapifyClient.validateRequiredComponents(result, "italia", "ROMA"))
                .doesNotThrowAnyException();
    }

    @Test
    void matchesIgnoringAccentsAndPunctuation() throws Exception {
        JsonNode result = result("Perù", "Forlì");

        assertThatCode(() -> GeoapifyClient.validateRequiredComponents(result, "Peru", "Forli"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsTownWhenCityFieldIsMissing() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {"country":"Italia", "town":"Frascati"}
                """);

        assertThatCode(() -> GeoapifyClient.validateRequiredComponents(result, "Italia", "Frascati"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAResultInAnotherCity() throws Exception {
        JsonNode result = result("Italia", "Milano");

        assertThatThrownBy(() -> GeoapifyClient.validateRequiredComponents(result, "Italia", "Roma"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Paese e città");
    }

    @Test
    void rejectsAResultInAnotherCountry() throws Exception {
        JsonNode result = result("Francia", "Roma");

        assertThatThrownBy(() -> GeoapifyClient.validateRequiredComponents(result, "Italia", "Roma"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void normalizesAndLimitsTheDatabaseAddress() {
        GeoPosition position = new GeoPosition("  Via   Roma  " + "x".repeat(300), 41.9, 12.5);

        assertThat(position.formattedAddress()).startsWith("Via Roma ");
        org.assertj.core.api.Assertions.assertThat(
                position.formattedAddress().codePointCount(0, position.formattedAddress().length()))
                .isEqualTo(255);
    }

    private JsonNode result(String country, String city) throws Exception {
        return objectMapper.readTree("""
                {"country":"%s", "city":"%s"}
                """.formatted(country, city));
    }

    private static GeoapifyServer serve(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> query = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/geocode/search", exchange -> {
            requests.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return new GeoapifyServer(server, query, requests);
    }

    private record GeoapifyServer(HttpServer server, AtomicReference<String> query,
                                  AtomicInteger requests) implements AutoCloseable {
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String decodedQuery() {
            return URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
        }

        int requestCount() {
            return requests.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
