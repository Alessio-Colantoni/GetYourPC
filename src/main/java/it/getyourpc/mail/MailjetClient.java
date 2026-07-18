package it.getyourpc.mail;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class MailjetClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailjetClient.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String secretKey;
    private final String senderEmail;
    private final String senderName;
    private final String sendUrl;

    public MailjetClient(RestClient.Builder restClientBuilder,
                         @Value("${mailjet.api-key:}") String apiKey,
                         @Value("${mailjet.secret-key:}") String secretKey,
                         @Value("${mailjet.sender-email:}") String senderEmail,
                         @Value("${mailjet.sender-name:GetYourPC}") String senderName,
                         @Value("${mailjet.base-url:https://api.mailjet.com}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.apiKey = clean(apiKey);
        this.secretKey = clean(secretKey);
        this.senderEmail = clean(senderEmail);
        this.senderName = clean(senderName).isBlank() ? "GetYourPC" : clean(senderName);
        String normalizedBaseUrl = clean(baseUrl).isBlank() ? "https://api.mailjet.com" : clean(baseUrl);
        this.sendUrl = normalizedBaseUrl.replaceAll("/+$", "") + "/v3.1/send";
    }

    public void sendVerificationCode(String recipientEmail, String code, String operation) {
        sendMessage(recipientEmail, "Codice di verifica GetYourPC",
                "Il codice per " + operation + " è " + code
                        + ". Scade tra 10 minuti. Se non hai richiesto questa operazione, ignora l'email.");
    }

    public void sendReviewerCredentials(String recipientEmail, String temporaryPassword) {
        sendMessage(recipientEmail, "Il tuo account reviewer GetYourPC",
                "È stato creato il tuo account reviewer GetYourPC. Password temporanea: "
                        + temporaryPassword + ". Accedi con questa email e cambia subito la password dal profilo.");
    }

    private void sendMessage(String recipientEmail, String subject, String text) {
        requireConfiguration();
        Map<String, Object> message = Map.of(
                "From", Map.of("Email", senderEmail, "Name", senderName),
                "To", List.of(Map.of("Email", recipientEmail)),
                "Subject", subject,
                "TextPart", text
        );
        try {
            JsonNode response = restClient.post().uri(sendUrl)
                    .headers(headers -> headers.setBasicAuth(apiKey, secretKey, StandardCharsets.UTF_8))
                    .body(Map.of("Messages", List.of(message), "AdvanceErrorHandling", true))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("Messages").isArray()
                    || response.path("Messages").isEmpty()) {
                LOGGER.error("Risposta Mailjet priva dello stato dei messaggi");
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Consegna Mailjet non confermata. Riprova tra poco");
            }
            if (response.path("Messages").valueStream()
                    .anyMatch(item -> !"success".equalsIgnoreCase(item.path("Status").asText()))) {
                String details = errorDetails(response);
                LOGGER.error("Mailjet ha rifiutato il messaggio: {}", details);
                throw mailjetFailure(details, 200, null);
            }
        } catch (RestClientResponseException exception) {
            String details = exception.getResponseBodyAsString();
            LOGGER.error("Errore Mailjet HTTP {}: {}", exception.getStatusCode().value(), details);
            throw mailjetFailure(details, exception.getStatusCode().value(), exception);
        } catch (ResourceAccessException exception) {
            LOGGER.error("Mailjet non raggiungibile", exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Mailjet non raggiungibile. Riprova tra poco", exception);
        } catch (RestClientException exception) {
            LOGGER.error("Risposta Mailjet non leggibile", exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Risposta Mailjet non valida. Riprova tra poco", exception);
        }
    }

    private void requireConfiguration() {
        if (apiKey.isBlank() || secretKey.isBlank() || senderEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Servizio email non configurato: verifica le variabili Mailjet su Render");
        }
    }

    private static ResponseStatusException mailjetFailure(String details, int status, Exception cause) {
        String normalized = details == null ? "" : details.toLowerCase();
        String reason;
        if (normalized.contains("send-0008") || normalized.contains("not authorized")) {
            reason = "Mittente Mailjet non autorizzato: verifica MAILJET_SENDER_EMAIL";
        } else if (status == 401 || normalized.contains("mj-0015")
                || normalized.contains("authorization credentials")) {
            reason = "Credenziali Mailjet non valide: verifica API key e Secret key";
        } else if (status == 403) {
            reason = "Invio Mailjet non autorizzato: verifica il mittente configurato";
        } else if (status == 429 || normalized.contains("rate limit")
                || normalized.contains("too many requests")) {
            reason = "Limite di invio Mailjet temporaneamente raggiunto";
        } else {
            reason = "Mailjet ha rifiutato l'invio. Controlla i log di Render";
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, reason, cause);
    }

    private static String errorDetails(JsonNode response) {
        StringBuilder details = new StringBuilder();
        response.path("Messages").forEach(message -> message.path("Errors").forEach(error -> {
            if (!details.isEmpty()) details.append("; ");
            details.append(error.path("ErrorCode").asText("errore-mailjet"));
            String errorMessage = error.path("ErrorMessage").asText();
            if (!errorMessage.isBlank()) details.append(": ").append(errorMessage);
        }));
        return details.isEmpty() ? "Risposta Mailjet con stato error" : details.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
