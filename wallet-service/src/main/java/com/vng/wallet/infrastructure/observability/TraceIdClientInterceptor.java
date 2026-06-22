package com.vng.wallet.infrastructure.observability;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * OB4 — interceptor outbound: đọc traceId trong {@link MDC} → đính {@code X-Trace-Id} vào MỌI request
 * RestClient đi ra (wallet → kyc / bank). Đối xứng inbound {@link TraceIdFilter}: một chỗ chèn cho mọi
 * outbound call, khỏi rải rác từng adapter.
 *
 * <p>Chốt (plan Task 3 Step 1): CHỈ forward khi MDC có traceId; thiếu → không set header (không tự
 * sinh ở outbound — root sinh ở entry inbound/worker, không ở đây).
 */
public class TraceIdClientInterceptor implements ClientHttpRequestInterceptor {

    public static final String HEADER = TraceIdFilter.HEADER;
    public static final String MDC_KEY = TraceIdFilter.MDC_KEY;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = MDC.get(MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            request.getHeaders().add(HEADER, traceId);
        }
        return execution.execute(request, body);
    }
}
