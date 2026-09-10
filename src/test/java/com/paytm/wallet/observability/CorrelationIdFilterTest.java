package com.paytm.wallet.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private String run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        var seen = new AtomicReference<String>();
        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(CorrelationIdFilter.MDC_KEY)));
        return seen.get();
    }

    @Test
    void inbound_correlation_id_is_honoured_and_echoed() throws Exception {
        var request = new MockHttpServletRequest("GET", "/wallets/x");
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123");
        var response = new MockHttpServletResponse();

        assertThat(run(request, response)).isEqualTo("abc-123");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123");
    }

    @Test
    void a_correlation_id_is_generated_when_absent_or_blank() throws Exception {
        var response = new MockHttpServletResponse();
        String generated = run(new MockHttpServletRequest("GET", "/x"), response);
        assertThat(generated).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(generated);

        var blank = new MockHttpServletRequest("GET", "/x");
        blank.addHeader(CorrelationIdFilter.HEADER, "  ");
        assertThat(run(blank, new MockHttpServletResponse())).isNotBlank().isNotEqualTo("  ");
    }

    @Test
    void mdc_is_cleared_after_the_request() throws Exception {
        run(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse());
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
