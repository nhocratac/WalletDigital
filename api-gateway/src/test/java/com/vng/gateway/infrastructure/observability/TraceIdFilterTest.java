package com.vng.gateway.infrastructure.observability;

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
 * Task 2 (OB1-OB2): gateway TraceIdFilter — đã forward + request-attribute, NAY thêm MDC.
 * traceId vào MDC trong chain (mọi log tự mang [%X{traceId}]), clear-in-finally, thread-reuse không rò.
 * Giữ nguyên hành vi forward (request-attribute + echo response header).
 */
class TraceIdFilterTest {

    private static final String MDC_KEY = "traceId";
    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    void incomingHeader_isPutIntoMdcAndAttributeDuringChain_andEchoedOnResponse()
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets/1");
        request.addHeader(TraceIdFilter.HEADER, "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcInChain = new AtomicReference<>();
        AtomicReference<Object> attrInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> {
            mdcInChain.set(MDC.get(MDC_KEY));
            attrInChain.set(req.getAttribute(TraceIdFilter.ATTR));
        };

        filter.doFilter(request, response, chain);

        assertThat(mdcInChain.get()).isEqualTo("abc");
        // forward behavior preserved
        assertThat(attrInChain.get()).isEqualTo("abc");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("abc");
        // OB3: cleared after the chain
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void missingHeader_generatesUuidIntoMdc() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcInChain.set(MDC.get(MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(mdcInChain.get()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(mdcInChain.get());
        assertThat(MDC.get(MDC_KEY)).isNull();
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/wallets/1");
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
        MockHttpServletRequest reqA = new MockHttpServletRequest("GET", "/api/wallets/1");
        reqA.addHeader(TraceIdFilter.HEADER, "trace-A");
        filter.doFilter(reqA, new MockHttpServletResponse(),
                (req, res) -> assertThat(MDC.get(MDC_KEY)).isEqualTo("trace-A"));

        MockHttpServletRequest reqB = new MockHttpServletRequest("GET", "/api/wallets/2");
        MockHttpServletResponse resB = new MockHttpServletResponse();
        AtomicReference<String> seenInB = new AtomicReference<>();
        filter.doFilter(reqB, resB, (req, res) -> seenInB.set(MDC.get(MDC_KEY)));

        assertThat(seenInB.get()).isNotBlank();
        assertThat(seenInB.get()).isNotEqualTo("trace-A");
        assertThat(MDC.get(MDC_KEY)).isNull();
    }
}
