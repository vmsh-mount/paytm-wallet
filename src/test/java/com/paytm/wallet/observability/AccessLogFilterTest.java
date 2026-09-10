package com.paytm.wallet.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paytm.wallet.config.AuthFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();
    private ListAppender<ILoggingEvent> appender;
    private final Logger accessLogger = (Logger) LoggerFactory.getLogger("com.paytm.wallet.access");

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        accessLogger.detachAppender(appender);
    }

    @Test
    void one_line_per_request_with_status_duration_and_user() throws Exception {
        var request = new MockHttpServletRequest("POST", "/transfers");
        request.setAttribute(AuthFilter.USER_ID_ATTRIBUTE, "alice");
        var response = new MockHttpServletResponse();
        response.setStatus(201);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(appender.list).hasSize(1);
        Map<String, Object> kv = appender.list.get(0).getKeyValuePairs().stream()
                .collect(Collectors.toMap(p -> p.key, p -> p.value));
        assertThat(kv).containsEntry("event", "http.access")
                .containsEntry("method", "POST")
                .containsEntry("path", "/transfers")
                .containsEntry("status", 201)
                .containsEntry("user_id", "alice")
                .containsKey("duration_ms");
    }

    @Test
    void logs_even_when_user_is_absent() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), new MockFilterChain());
        assertThat(appender.list).hasSize(1);
        Map<String, Object> kv = appender.list.get(0).getKeyValuePairs().stream()
                .collect(java.util.HashMap::new, (m, p) -> m.put(p.key, p.value), java.util.HashMap::putAll);
        assertThat(kv.get("user_id")).isNull();
    }
}
