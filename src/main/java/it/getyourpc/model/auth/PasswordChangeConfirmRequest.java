package it.getyourpc.model.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordChangeConfirmRequest(
        @NotBlank(message = "Inserisci il codice")
        @Pattern(regexp = "\\d{5}", message = "Il codice deve contenere 5 cifre") String code,
        @NotBlank(message = "Inserisci la nuova password")
        @Size(max = 200, message = "Password troppo lunga") String newPassword) {
}
