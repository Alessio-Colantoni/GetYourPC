package it.getyourpc.model.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterStartRequest(
        @NotBlank(message = "Inserisci il nome") @Size(max = 255, message = "Nome troppo lungo") String name,
        @NotBlank(message = "Inserisci il cognome") @Size(max = 255, message = "Cognome troppo lungo") String surname,
        @NotBlank(message = "Inserisci l'email") @Email(message = "Email non valida")
        @Size(max = 255, message = "Email troppo lunga") String email,
        @NotBlank(message = "Inserisci la password") @Size(max = 200, message = "Password troppo lunga")
        String password) {
}
