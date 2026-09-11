package com.paytm.wallet.config;

import java.io.ByteArrayOutputStream;
import java.net.URI;
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

    /**
     * Strict percent-decoding of a URI user-info component — {@code %XX} escapes
     * only. Deliberately {@code URLDecoder}-free: that class implements
     * {@code application/x-www-form-urlencoded}, which also turns a literal
     * {@code +} into a space. That's correct for a query string, wrong for
     * user-info (RFC 3986 §3.2.1), and would silently mangle a generated
     * password that happens to contain a {@code +}.
     */
    private static String decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private RenderDatabaseUrl() {}
}
