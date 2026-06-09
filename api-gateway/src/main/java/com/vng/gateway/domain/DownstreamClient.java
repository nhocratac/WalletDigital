package com.vng.gateway.domain;

import java.util.Map;

/**
 * PORT: gửi một request đã ký xuống downstream và trả về response.
 * Dùng record lồng để mô tả request/response một cách thuần khiết.
 */
public interface DownstreamClient {

    record DownstreamRequest(
            String method,
            String baseUrl,
            String path,
            byte[] body,
            Map<String, String> headers
    ) {}

    record DownstreamResponse(
            int status,
            byte[] body,
            Map<String, String> headers
    ) {}

    DownstreamResponse forward(DownstreamRequest request);
}
