package it.getyourpc.model.listing;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum ListingType {
    DESKTOP, LAPTOP;

    public static ListingType from(String value) {
        try {
            return ListingType.valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo di PC non valido");
        }
    }
}
