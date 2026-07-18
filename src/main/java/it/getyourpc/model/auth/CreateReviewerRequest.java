package it.getyourpc.model.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateReviewerRequest(
        @NotBlank(message = "Inserisci il nome") @Size(max = 255) String name,
        @NotBlank(message = "Inserisci il cognome") @Size(max = 255) String surname,
        @NotBlank(message = "Inserisci l'email") @Email(message = "Email non valida")
        @Size(max = 255) String email) {
}
