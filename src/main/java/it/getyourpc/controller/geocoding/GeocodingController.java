package it.getyourpc.controller.geocoding;

import it.getyourpc.model.common.RequestRateLimiter;
import it.getyourpc.model.geocoding.GeoPosition;
import it.getyourpc.model.geocoding.GeoapifyClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/geocoding")
public class GeocodingController {
    private final GeoapifyClient geoapifyClient;
    private final RequestRateLimiter rateLimiter;

    public GeocodingController(GeoapifyClient geoapifyClient, RequestRateLimiter rateLimiter) {
        this.geoapifyClient = geoapifyClient;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public GeoPosition geocode(@RequestParam String country,
                               @RequestParam String city,
                               @RequestParam(required = false) String address,
                               HttpServletRequest request) {
        rateLimiter.check("geocoding", request.getRemoteAddr(), 60, Duration.ofMinutes(1),
                "Troppe richieste di geocodifica. Riprova tra un minuto");
        return geoapifyClient.geocodeLocation(country, city, address);
    }
}
