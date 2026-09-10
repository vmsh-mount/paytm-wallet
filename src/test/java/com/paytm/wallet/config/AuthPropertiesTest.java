package com.paytm.wallet.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthPropertiesTest {

    private static AuthProperties props(String tokens) {
        AuthProperties p = new AuthProperties();
        p.setTokens(tokens);
        return p;
    }

    @Test
    void parses_token_user_pairs() {
        assertThat(props("t1:u1,t2:u2").tokenToUserId())
                .containsOnly(
                        org.assertj.core.data.MapEntry.entry("t1", "u1"),
                        org.assertj.core.data.MapEntry.entry("t2", "u2"));
    }

    @Test
    void tolerates_whitespace_and_blank_entries() {
        assertThat(props(" t1:u1 , , t2:u2 ").tokenToUserId())
                .containsOnlyKeys("t1", "t2");
    }

    @Test
    void empty_config_is_an_empty_map() {
        assertThat(props("").tokenToUserId()).isEmpty();
        assertThat(props("   ").tokenToUserId()).isEmpty();
    }

    @Test
    void rejects_entry_without_colon() {
        assertThatThrownBy(() -> props("t1u1").tokenToUserId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void rejects_entry_with_empty_token_or_user() {
        assertThatThrownBy(() -> props(":u1").tokenToUserId()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> props("t1:").tokenToUserId()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> props("a:b:c").tokenToUserId()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejects_duplicate_token() {
        assertThatThrownBy(() -> props("t1:u1,t1:u2").tokenToUserId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }
}
