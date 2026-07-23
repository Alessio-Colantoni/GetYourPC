package it.getyourpc.model.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeStartRequest(
        @NotBlank(message = "Inserisci la password attuale")
        @Size(max = 200, message = "Password troppo lunga") String currentPassword) {
}
