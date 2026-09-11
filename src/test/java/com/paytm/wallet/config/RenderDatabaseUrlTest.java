package com.paytm.wallet.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RenderDatabaseUrlTest {

    @Test
    void parses_a_render_style_url_into_jdbc_url_and_credentials() {
        var info = RenderDatabaseUrl.parse("postgres://myuser:mypass@dpg-host.oregon-postgres.render.com:5432/mydb");

        assertThat(info.jdbcUrl()).isEqualTo("jdbc:postgresql://dpg-host.oregon-postgres.render.com:5432/mydb");
        assertThat(info.username()).isEqualTo("myuser");
        assertThat(info.password()).isEqualTo("mypass");
    }

    @Test
    void accepts_the_postgresql_scheme_too() {
        var info = RenderDatabaseUrl.parse("postgresql://u:p@h:5432/db");
        assertThat(info.jdbcUrl()).startsWith("jdbc:postgresql://h:5432/db");
    }

    @Test
    void defaults_the_port_when_absent() {
        var info = RenderDatabaseUrl.parse("postgres://u:p@h/db");
        assertThat(info.jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/db");
    }

    @Test
    void preserves_query_params_and_decodes_percent_encoded_credentials() {
        var info = RenderDatabaseUrl.parse("postgres://u%40x:p%3Aass@h:5432/db?sslmode=require");
        assertThat(info.username()).isEqualTo("u@x");
        assertThat(info.password()).isEqualTo("p:ass");
        assertThat(info.jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/db?sslmode=require");
    }

    @Test
    void isRenderStyle_is_false_for_a_jdbc_url_or_null() {
        assertThat(RenderDatabaseUrl.isRenderStyle("jdbc:postgresql://localhost:5432/wallet")).isFalse();
        assertThat(RenderDatabaseUrl.isRenderStyle(null)).isFalse();
        assertThat(RenderDatabaseUrl.isRenderStyle("postgres://u:p@h/db")).isTrue();
        assertThat(RenderDatabaseUrl.isRenderStyle("postgresql://u:p@h/db")).isTrue();
    }
}
