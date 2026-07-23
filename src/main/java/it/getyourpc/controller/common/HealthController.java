package it.getyourpc.controller.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        Long result = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Users", Long.class);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Database non disponibile");
        }
        return Map.of("status", "ok", "database", "ok");
    }

    @GetMapping("/live")
    public Map<String, String> live() {
        return Map.of("status", "ok");
    }
}
