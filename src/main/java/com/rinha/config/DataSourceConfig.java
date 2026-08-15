package com.rinha.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * DATABASE_URL can arrive either as a JDBC url ("jdbc:postgresql://host:5432/db")
 * or as a plain postgres url ("postgres://user:pass@host:5432/db"). This normalizes
 * either form into something the pgjdbc driver accepts.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public HikariDataSource dataSource(@Value("${DATABASE_URL}") String databaseUrl) {
        HikariConfig config = new HikariConfig();

        String jdbcUrl = databaseUrl;
        String username = null;
        String password = null;

        if (!databaseUrl.startsWith("jdbc:")) {
            try {
                URI uri = new URI(databaseUrl);
                if (uri.getUserInfo() != null) {
                    String[] creds = uri.getUserInfo().split(":", 2);
                    username = creds[0];
                    password = creds.length > 1 ? creds[1] : "";
                }
                int port = uri.getPort() == -1 ? 5432 : uri.getPort();
                jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
            } catch (URISyntaxException e) {
                throw new IllegalStateException("Invalid DATABASE_URL: " + databaseUrl, e);
            }
        }

        config.setJdbcUrl(jdbcUrl);
        if (username != null) {
            config.setUsername(username);
            config.setPassword(password);
        }
        config.setDriverClassName("org.postgresql.Driver");
        config.setPoolName("rinha-pool");
        // App gets 1.5 CPU / 3GB total; keep the pool modest so Postgres
        // (0.5 CPU / 1GB) isn't overwhelmed with contended connections.
        config.setMaximumPoolSize(32);
        config.setMinimumIdle(8);
        config.setConnectionTimeout(5_000);
        config.addDataSourceProperty("reWriteBatchedInserts", "true");

        return new HikariDataSource(config);
    }
}
