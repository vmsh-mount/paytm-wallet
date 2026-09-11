package com.paytm.wallet.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class RenderDatabaseUrlEnvironmentPostProcessorTest {

    private final RenderDatabaseUrlEnvironmentPostProcessor processor =
            new RenderDatabaseUrlEnvironmentPostProcessor();

    @Test
    void bridges_a_render_style_DATABASE_URL_to_spring_datasource_properties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "postgres://u:p@dpg-host:5432/db");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://dpg-host:5432/db");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p");
    }

    @Test
    void leaves_an_already_jdbc_url_untouched() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DATABASE_URL", "jdbc:postgresql://localhost:5432/wallet");

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @Test
    void does_nothing_when_DATABASE_URL_is_absent() {
        MockEnvironment env = new MockEnvironment();
        processor.postProcessEnvironment(env, null);
        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }
}
