package it.getyourpc.model.listing;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ListingCreateRequest(
        @NotBlank(message = "Seleziona il tipo di PC") String type,
        @NotNull(message = "Inserisci il prezzo")
        @DecimalMin(value = "0.01", message = "Il prezzo deve essere positivo")
        @DecimalMax(value = "100000.00", message = "Il prezzo non può superare 100.000 euro")
        @Digits(integer = 10, fraction = 2, message = "Il prezzo può avere al massimo due decimali") BigDecimal price,
        @NotBlank(message = "Inserisci il paese") @Size(max = 100) String country,
        @NotBlank(message = "Inserisci la città") @Size(max = 100) String city,
        @Size(max = 255) String address,
        @Size(max = 255) String brand,
        @Size(max = 255) String model,
        @DecimalMin(value = "5.0", message = "La dimensione dello schermo non è valida")
        @DecimalMax(value = "30.0", message = "La dimensione dello schermo non è valida")
        @Digits(integer = 2, fraction = 2, message = "La dimensione dello schermo non è valida") BigDecimal screenSize,
        @NotBlank(message = "Inserisci la CPU") @Size(max = 255) String cpu,
        @Size(max = 255) String motherboard,
        @NotBlank(message = "Inserisci la GPU") @Size(max = 255) String gpu,
        @NotBlank(message = "Inserisci la RAM") @Size(max = 255) String ram,
        @NotBlank(message = "Inserisci la memoria") @Size(max = 255) String memory,
        @Size(max = 255) String power,
        @Size(max = 255) String cpuHeat,
        @Size(max = 255) String pcCase,
        boolean showPhone) {
}
