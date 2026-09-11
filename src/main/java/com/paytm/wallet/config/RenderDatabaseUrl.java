package com.paytm.wallet.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Bridges Render's managed-Postgres connection string
 * ({@code postgres://user:pass@host:port/db?params}) to the three properties
 * Spring's JDBC datasource wants: a {@code jdbc:} URL plus separate
 * username/password. The pgjdbc driver does not read credentials out of the
 * URL user-info, so they must be split out explicitly.
 */
public final class RenderDatabaseUrl {

    public record JdbcConnectionInfo(String jdbcUrl, String username, String password) {}

    /** True for {@code postgres://} / {@code postgresql://}; false for anything else (incl. already-JDBC). */
    public static boolean isRenderStyle(String url) {
        return url != null && (url.startsWith("postgres://") || url.startsWith("postgresql://"));
    }

    public static JdbcConnectionInfo parse(String renderUrl) {
        URI uri = URI.create(renderUrl);
        String userInfo = uri.getUserInfo();
        String username = "";
        String password = "";
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? "" : decode(userInfo.substring(colon + 1));
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath();
        if (uri.getQuery() != null && !uri.getQuery().isBlank()) {
            jdbcUrl += "?" + uri.getQuery();
        }
        return new JdbcConnectionInfo(jdbcUrl, username, password);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private RenderDatabaseUrl() {}
}
