package com.paytm.wallet.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest {

    private static final String GOOD_TOKEN = "tok-alice-secret";

    private AuthFilter filter;
    private ObjectMapper objectMapper;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setTokens(GOOD_TOKEN + ":alice,tok-bob:bob");
        // mirror the app's Jackson config (application.yml: SNAKE_CASE)
        objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        filter = new AuthFilter(props, objectMapper);

        logs = new ListAppender<>();
        logs.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuthFilter.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuthFilter.class);
        logger.detachAppender(logs);
        logger.setLevel(null);
        assertThat(org.slf4j.MDC.get(AuthFilter.MDC_USER_ID)).isNull();
        assertThat(RequestContext.current()).isEmpty();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void valid_token_proceeds_and_binds_request_context() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallets");
        request.addHeader("Authorization", "Bearer " + GOOD_TOKEN);

        AtomicReference<String> seenUser = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenUser.set(RequestContext.userId());
            }
        };

        MockHttpServletResponse response = run(request, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenUser.get()).isEqualTo("alice");
    }

    @Test
    void tokens_map_to_their_own_users() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallets");
        request.addHeader("Authorization", "Bearer tok-bob");
        AtomicReference<String> seenUser = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenUser.set(RequestContext.userId());
            }
        };

        run(request, chain);

        assertThat(seenUser.get()).isEqualTo("bob");
    }

    @Test
    void missing_header_is_401_json_with_correlation_id() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/wallets");
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(request, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("unauthorized");
        assertThat(body.has("correlation_id")).isTrue();
        assertThat(chain.getRequest()).isNull(); // chain not invoked
    }

    @Test
    void malformed_and_unknown_tokens_are_401() throws Exception {
        for (String header : new String[]{"Basic abc", "Bearer", "Bearer ", "Bearer nope", "bearer " + GOOD_TOKEN}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wallets/x");
            request.addHeader("Authorization", header);
            MockHttpServletResponse response = run(request, new MockFilterChain());
            assertThat(response.getStatus()).as("header=%s", header).isEqualTo(401);
        }
    }

    @Test
    void actuator_paths_bypass_auth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(request, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull(); // proceeded
    }

    @Test
    void token_value_is_never_logged() throws Exception {
        MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/wallets");
        ok.addHeader("Authorization", "Bearer " + GOOD_TOKEN);
        run(ok, new MockFilterChain());

        MockHttpServletRequest bad = new MockHttpServletRequest("POST", "/wallets");
        bad.addHeader("Authorization", "Bearer " + GOOD_TOKEN + "-tampered");
        run(bad, new MockFilterChain());

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(GOOD_TOKEN));
    }
}
