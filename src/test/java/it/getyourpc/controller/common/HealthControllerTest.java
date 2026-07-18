package it.getyourpc.controller.common;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    @Test
    void healthChecksTheDatabase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Users", Long.class)).thenReturn(0L);

        assertThat(new HealthController(jdbcTemplate).health())
                .isEqualTo(Map.of("status", "ok", "database", "ok"));
    }

    @Test
    void healthFailsWhenTheDatabaseProbeIsInvalid() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM Users", Long.class)).thenReturn(null);

        assertThatThrownBy(() -> new HealthController(jdbcTemplate).health())
                .hasMessageContaining("503");
    }
}
