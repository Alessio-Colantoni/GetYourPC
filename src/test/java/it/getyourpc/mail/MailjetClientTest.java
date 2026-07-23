package it.getyourpc.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MailjetClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsTheOfficialV31PayloadWithBasicAuthentication() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (TestServer server = server(authorization, body, 200)) {
            MailjetClient client = new MailjetClient(RestClient.builder(), "public-key", "secret-key",
                    "sender@example.com", "GetYourPC", server.baseUrl());

            client.sendVerificationCode("user@example.com", "04219", "completare la registrazione");

            assertThat(authorization.get()).isEqualTo("Basic " + Base64.getEncoder()
                    .encodeToString("public-key:secret-key".getBytes(StandardCharsets.UTF_8)));
            JsonNode payload = objectMapper.readTree(body.get());
            assertThat(payload.path("AdvanceErrorHandling").asBoolean()).isTrue();
            JsonNode message = payload.path("Messages").path(0);
            assertThat(message.path("From").path("Email").asText()).isEqualTo("sender@example.com");
            assertThat(message.path("To").path(0).path("Email").asText()).isEqualTo("user@example.com");
            assertThat(message.path("TextPart").asText()).contains("04219").contains("10 minuti");
        }
    }

    @Test
    void sendsReviewerCredentialsWithoutExposingThemOutsideTheMailPayload() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (TestServer server = server(new AtomicReference<>(), body, 200)) {
            MailjetClient client = new MailjetClient(RestClient.builder(), "public-key", "secret-key",
                    "sender@example.com", "GetYourPC", server.baseUrl());

            client.sendReviewerCredentials("reviewer@example.com", "Temporary-42!");

            JsonNode message = objectMapper.readTree(body.get()).path("Messages").path(0);
            assertThat(message.path("To").path(0).path("Email").asText())
                    .isEqualTo("reviewer@example.com");
            assertThat(message.path("Subject").asText()).containsIgnoringCase("reviewer");
            assertThat(message.path("TextPart").asText())
                    .contains("Temporary-42!").contains("cambia subito la password");
        }
    }

    @Test
    void rejectsMissingConfigurationAndMapsMailjetErrors() throws Exception {
        MailjetClient missing = new MailjetClient(RestClient.builder(), "", "", "", "GetYourPC",
                "http://127.0.0.1:1");
        assertThatThrownBy(() -> missing.sendVerificationCode("user@example.com", "12345", "registrarti"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        String invalidCredentials = "{\"ErrorCode\":\"mj-0015\","
                + "\"ErrorMessage\":\"Invalid authorization credentials\"}";
        try (TestServer server = server(new AtomicReference<>(), new AtomicReference<>(),
                401, invalidCredentials)) {
            MailjetClient failing = new MailjetClient(RestClient.builder(), "public", "secret",
                    "sender@example.com", "GetYourPC", server.baseUrl());
            assertThatThrownBy(() -> failing.sendVerificationCode(
                    "user@example.com", "12345", "registrarti"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> {
                        ResponseStatusException response = (ResponseStatusException) error;
                        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                        assertThat(response.getReason()).contains("Credenziali Mailjet non valide");
                    });
        }
    }

    @Test
    void reportsSenderValidationErrorsReturnedWithHttpSuccess() throws Exception {
        String response = "{\"Messages\":[{\"Status\":\"error\",\"Errors\":[{"
                + "\"ErrorCode\":\"send-0008\","
                + "\"ErrorMessage\":\"Sender email address is not authorized\"}]}]}";
        try (TestServer server = server(new AtomicReference<>(), new AtomicReference<>(), 200, response)) {
            MailjetClient client = new MailjetClient(RestClient.builder(), "public", "secret",
                    "sender@example.com", "GetYourPC", server.baseUrl());

            assertThatThrownBy(() -> client.sendVerificationCode(
                    "user@example.com", "12345", "registrarti"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(error -> assertThat(((ResponseStatusException) error).getReason())
                            .contains("MAILJET_SENDER_EMAIL"));
        }
    }

    private static TestServer server(AtomicReference<String> authorization,
                                     AtomicReference<String> body, int status) throws Exception {
        String response = status >= 200 && status < 300
                ? "{\"Messages\":[{\"Status\":\"success\"}]}"
                : "";
        return server(authorization, body, status, response);
    }

    private static TestServer server(AtomicReference<String> authorization,
                                     AtomicReference<String> body, int status,
                                     String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3.1/send", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            if (response.length > 0) exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return new TestServer(server);
    }

    private record TestServer(HttpServer server) implements AutoCloseable {
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
