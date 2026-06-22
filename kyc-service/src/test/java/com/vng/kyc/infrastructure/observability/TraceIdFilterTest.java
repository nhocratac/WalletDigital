package com.vng.kyc.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2 (OB1-OB3): kyc TraceIdFilter — continue-or-generate vào MDC, clear-in-finally,
 * thread-reuse không rò. Chạy SỚM NHẤT (trước InternalAuthFilter @Order(1)) → log 401/403 vẫn có traceId.
 */
class TraceIdFilterTest {

    private static final String MDC_KEY = "traceId";
    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void incomingHeader_isPutIntoMdcDuringChain_andEchoedOnResponse() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/kyc/cases/u1/revoke");
        request.addHeader(TraceIdFilter.HEADER, "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(MDC.get(MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(seenInChain.get()).isEqualTo("abc");
        assertThat(MDC.get(MDC_KEY)).isNull();
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc");
    }

    @Test
    void missingHeader_generatesUuidIntoMdc() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/kyc/cases/u1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(MDC.get(MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(seenInChain.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(seenInChain.get());
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/kyc/cases/u1");
        request.addHeader(TraceIdFilter.HEADER, "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain boom = (req, res) -> {
            assertThat(MDC.get(MDC_KEY)).isEqualTo("abc");
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, boom))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void threadReuse_doesNotLeakPreviousTraceId() throws ServletException, IOException {
        MockHttpServletRequest reqA = new MockHttpServletRequest("GET", "/kyc/cases/u1");
        reqA.addHeader(TraceIdFilter.HEADER, "trace-A");
        filter.doFilter(reqA, new MockHttpServletResponse(),
                (req, res) -> assertThat(MDC.get(MDC_KEY)).isEqualTo("trace-A"));

        MockHttpServletRequest reqB = new MockHttpServletRequest("GET", "/kyc/cases/u2");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        AtomicReference<String> seenInB = new AtomicReference<>();
        filter.doFilter(reqB, resB, (req, res) -> seenInB.set(MDC.get(MDC_KEY)));

        assertThat(seenInB.get()).isNotBlank();
        assertThat(seenInB.get()).isNotEqualTo("trace-A");
        assertThat(MDC.get(MDC_KEY)).isNull();
    }
}
