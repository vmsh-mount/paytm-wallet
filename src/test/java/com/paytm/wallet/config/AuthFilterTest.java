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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;

    /** MockHttpServletRequest doesn't derive servletPath from the URI — set it explicitly. */
    private static MockHttpServletRequest req(String method, String servletPath) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, servletPath);
        r.setServletPath(servletPath);
        return r;
    }

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setTokens(GOOD_TOKEN + ":alice,tok-bob:bob");
        filter = new AuthFilter(props, objectMapper);

        logs = new ListAppender<>();
        logs.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuthFilter.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuthFilter.class)).detachAppender(logs);
        assertThat(org.slf4j.MDC.get(AuthFilter.MDC_USER_ID)).isNull();
        assertThat(RequestContext.current()).isEmpty();
    }

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockFilterChain capturingChain(AtomicReference<String> seenUser) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seenUser.set(RequestContext.userId());
            }
        };
    }

    @Test
    void valid_token_proceeds_and_binds_request_context() throws Exception {
        MockHttpServletRequest request = req("POST", "/wallets");
        request.addHeader("Authorization", "Bearer " + GOOD_TOKEN);
        AtomicReference<String> seenUser = new AtomicReference<>();

        MockHttpServletResponse response = run(request, capturingChain(seenUser));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seenUser.get()).isEqualTo("alice");
    }

    @Test
    void tokens_map_to_their_own_users() throws Exception {
        MockHttpServletRequest request = req("POST", "/wallets");
        request.addHeader("Authorization", "Bearer tok-bob");
        AtomicReference<String> seenUser = new AtomicReference<>();

        run(request, capturingChain(seenUser));

        assertThat(seenUser.get()).isEqualTo("bob");
    }

    @Test
    void missing_header_is_401_json_with_correlation_id() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(req("POST", "/wallets"), chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("unauthorized");
        assertThat(body.get("correlation_id").asText()).isNotBlank();
        assertThat(chain.getRequest()).isNull(); // chain not invoked
    }

    @Test
    void malformed_and_unknown_tokens_are_401() throws Exception {
        for (String header : new String[]{"Basic abc", "Bearer", "Bearer ", "Bearer nope", "bearer " + GOOD_TOKEN}) {
            MockHttpServletRequest request = req("GET", "/wallets/x");
            request.addHeader("Authorization", header);
            MockHttpServletResponse response = run(request, new MockFilterChain());
            assertThat(response.getStatus()).as("header=%s", header).isEqualTo(401);
        }
    }

    @Test
    void actuator_paths_bypass_auth() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        MockHttpServletResponse response = run(req("GET", "/actuator/health"), chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull(); // proceeded
    }

    @Test
    void non_actuator_path_is_not_bypassed() throws Exception {
        // servletPath is already container-normalised; a traversal that resolves out
        // of /actuator must not be treated as open.
        MockHttpServletResponse response = run(req("GET", "/wallets"), new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void token_value_is_never_logged() throws Exception {
        MockHttpServletRequest ok = req("POST", "/wallets");
        ok.addHeader("Authorization", "Bearer " + GOOD_TOKEN);
        run(ok, new MockFilterChain());

        MockHttpServletRequest bad = req("POST", "/wallets");
        bad.addHeader("Authorization", "Bearer " + GOOD_TOKEN + "-tampered");
        run(bad, new MockFilterChain());

        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains(GOOD_TOKEN));
    }
}
