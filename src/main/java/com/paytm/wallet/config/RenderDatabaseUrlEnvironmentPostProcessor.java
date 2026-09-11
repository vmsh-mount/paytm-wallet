package com.paytm.wallet.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render (and most managed-Postgres hosts) hand the app one {@code DATABASE_URL}
 * in {@code postgres://user:pass@host:port/db} form. If that's what we see,
 * split it into {@code spring.datasource.{url,username,password}} — added as a
 * property source ahead of {@code application.yml}, so its
 * {@code ${DATABASE_URL:jdbc:...}} default is overridden rather than fed the
 * raw non-JDBC URL. A local/compose {@code jdbc:postgresql://...} value is left
 * untouched (this is a no-op).
 *
 * <p>Registered via {@code META-INF/spring.factories} — runs before the
 * datasource bean is created, too early for a regular {@code @Configuration}.
 * {@code getOrder()} is documentation, not a correctness requirement: because
 * we {@link org.springframework.core.env.MutablePropertySources#addFirst
 * addFirst} rather than replace, the bridged values win regardless of exactly
 * when — before or after {@code application.yml} loads — this runs.
 */
public class RenderDatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("DATABASE_URL");
        if (!RenderDatabaseUrl.isRenderStyle(raw)) {
            return;
        }
        RenderDatabaseUrl.JdbcConnectionInfo info = RenderDatabaseUrl.parse(raw);
        Map<String, Object> bridged = new LinkedHashMap<>();
        bridged.put("spring.datasource.url", info.jdbcUrl());
        bridged.put("spring.datasource.username", info.username());
        bridged.put("spring.datasource.password", info.password());
        environment.getPropertySources().addFirst(new MapPropertySource("render-database-url-bridge", bridged));
    }
}
