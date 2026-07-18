package it.getyourpc.model.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterConfirmRequest(
        @NotBlank(message = "Inserisci l'email") @Email(message = "Email non valida")
        @Size(max = 255, message = "Email troppo lunga") String email,
        @NotBlank(message = "Inserisci il codice")
        @Pattern(regexp = "\\d{5}", message = "Il codice deve contenere 5 cifre") String code) {
}
