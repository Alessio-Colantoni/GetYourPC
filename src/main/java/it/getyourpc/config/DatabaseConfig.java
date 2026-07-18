package it.getyourpc.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Configuration
public class DatabaseConfig {
    @Bean
    DataSource dataSource(Environment environment) {
        DatabaseSettings settings = DatabaseSettings.from(environment);
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(settings.jdbcUrl());
        dataSource.setUsername(settings.username());
        dataSource.setPassword(settings.password());
        dataSource.setMaximumPoolSize(settings.poolSize());
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(20_000);
        return dataSource;
    }

    record DatabaseSettings(String jdbcUrl, String username, String password, int poolSize) {
        static DatabaseSettings from(Environment environment) {
            String rawUrl = firstPresent(environment.getProperty("JDBC_DATABASE_URL"),
                    environment.getProperty("DATABASE_URL"));
            if (rawUrl == null) {
                throw new IllegalStateException("JDBC_DATABASE_URL o DATABASE_URL non configurata");
            }

            URI uri = parsePostgresUri(rawUrl);
            String embeddedUser = null;
            String embeddedPassword = null;
            String userInfo = uri.getUserInfo();
            if (userInfo != null) {
                int separator = userInfo.indexOf(':');
                embeddedUser = separator >= 0 ? userInfo.substring(0, separator) : userInfo;
                embeddedPassword = separator >= 0 ? userInfo.substring(separator + 1) : null;
            }

            String username = firstPresent(environment.getProperty("DB_USERNAME"), embeddedUser);
            String password = firstPresent(environment.getProperty("DB_PASSWORD"), embeddedPassword);
            if (username == null || password == null) {
                throw new IllegalStateException(
                        "Credenziali database mancanti: configurare DB_USERNAME e DB_PASSWORD o includerle nella URI");
            }

            int poolSize = parsePoolSize(environment.getProperty("DB_POOL_SIZE"));
            return new DatabaseSettings(toJdbcUrl(uri), username, password, poolSize);
        }

        private static URI parsePostgresUri(String rawUrl) {
            String value = rawUrl.trim();
            if (value.startsWith("jdbc:")) value = value.substring("jdbc:".length());
            URI uri;
            try {
                uri = new URI(value);
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("La URL del database non è valida", exception);
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("postgres") || scheme.equals("postgresql")) || uri.getHost() == null) {
                throw new IllegalStateException("La URL deve essere PostgreSQL (postgres://, postgresql:// o jdbc:postgresql://)");
            }
            return uri;
        }

        private static String toJdbcUrl(URI uri) {
            try {
                String query = secureQuery(uri);
                URI withoutCredentials = new URI("postgresql", null, uri.getHost(), uri.getPort(),
                        uri.getPath(), query, null);
                return "jdbc:" + withoutCredentials;
            } catch (URISyntaxException exception) {
                throw new IllegalStateException("Impossibile normalizzare la URL PostgreSQL", exception);
            }
        }

        private static String secureQuery(URI uri) {
            String query = uri.getQuery();
            if (isLocalDatabase(uri.getHost())) return query;

            String sslMode = null;
            if (query != null) {
                for (String parameter : query.split("&")) {
                    int separator = parameter.indexOf('=');
                    String name = separator >= 0 ? parameter.substring(0, separator) : parameter;
                    if (name.equalsIgnoreCase("sslmode")) {
                        sslMode = separator >= 0 ? parameter.substring(separator + 1) : "";
                        break;
                    }
                }
            }
            if (sslMode == null || sslMode.isBlank()) {
                return query == null || query.isBlank() ? "sslmode=require" : query + "&sslmode=require";
            }
            String normalized = sslMode.toLowerCase(Locale.ROOT);
            if (!(normalized.equals("require") || normalized.equals("verify-ca")
                    || normalized.equals("verify-full"))) {
                throw new IllegalStateException(
                        "Le connessioni PostgreSQL remote devono usare sslmode=require, verify-ca o verify-full");
            }
            return query;
        }

        private static boolean isLocalDatabase(String host) {
            if (host == null) return false;
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.equals("localhost") || normalized.equals("127.0.0.1")
                    || normalized.equals("::1") || normalized.equals("0:0:0:0:0:0:0:1");
        }

        private static int parsePoolSize(String value) {
            if (value == null || value.isBlank()) return 5;
            try {
                int size = Integer.parseInt(value.trim());
                if (size < 1 || size > 20) throw new NumberFormatException();
                return size;
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("DB_POOL_SIZE deve essere compreso tra 1 e 20");
            }
        }

        private static String firstPresent(String first, String second) {
            if (first != null && !first.isBlank()) return first.trim();
            if (second != null && !second.isBlank()) return second.trim();
            return null;
        }
    }
}
