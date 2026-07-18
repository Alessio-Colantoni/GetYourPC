package it.getyourpc.model.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @NotBlank(message = "Inserisci il nome") @Size(max = 255) String name,
        @NotBlank(message = "Inserisci il cognome") @Size(max = 255) String surname,
        @Pattern(regexp = "^$|^[+0-9][0-9 .()/-]{5,30}$", message = "Numero di telefono non valido")
        String phone) {
}
