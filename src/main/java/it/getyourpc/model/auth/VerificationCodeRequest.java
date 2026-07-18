package it.getyourpc.model.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerificationCodeRequest(
        @NotBlank(message = "Inserisci il codice")
        @Pattern(regexp = "\\d{5}", message = "Il codice deve contenere 5 cifre") String code) {
}
