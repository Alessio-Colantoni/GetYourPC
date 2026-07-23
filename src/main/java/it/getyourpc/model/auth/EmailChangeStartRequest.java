package it.getyourpc.model.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailChangeStartRequest(
        @NotBlank(message = "Inserisci la password attuale")
        @Size(max = 200, message = "Password troppo lunga") String currentPassword,
        @NotBlank(message = "Inserisci la nuova email") @Email(message = "Email non valida")
        @Size(max = 255, message = "Email troppo lunga") String newEmail) {
}
