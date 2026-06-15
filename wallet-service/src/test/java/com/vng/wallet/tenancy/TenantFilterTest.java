package com.vng.wallet.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantFilterTest {

    private final TenantFilter filter = new TenantFilter();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void sets_context_during_chain_and_clears_after() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantFilter.HEADER, "acme");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(TenantContext.get());

        filter.doFilter(request, response, chain);

        assertThat(seenInChain.get()).isEqualTo("acme");
        // T4: cleared after the chain
        assertThat(TenantContext.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void clears_context_even_when_chain_throws() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantFilter.HEADER, "acme");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain boom = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, boom))
                .isInstanceOf(ServletException.class);
        // T4: finally must clear even on exception
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void missing_header_returns_400_and_does_not_enter_chain() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        FilterChain chain = (req, res) -> chainCalled.set(true);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(chainCalled.get()).isFalse();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void blank_header_returns_400() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TenantFilter.HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            throw new AssertionError("chain must not run on blank header");
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void thread_reuse_does_not_leak_previous_tenant() throws ServletException, IOException {
        // First request for acme on this thread
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        req1.addHeader(TenantFilter.HEADER, "acme");
        filter.doFilter(req1, new MockHttpServletResponse(), (req, res) -> {
            assertThat(TenantContext.get()).isEqualTo("acme");
        });

        // Same thread reused; a request missing the header must NOT see acme's context
        MockHttpServletRequest req2 = new MockHttpServletRequest();
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.doFilter(req2, res2, (req, res) -> chainCalled.set(true));

        assertThat(res2.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(chainCalled.get()).isFalse();
        assertThat(TenantContext.get()).isNull();
    }
}
