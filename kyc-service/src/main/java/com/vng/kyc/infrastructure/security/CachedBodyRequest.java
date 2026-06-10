package com.vng.kyc.infrastructure.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Đọc body một lần, cho phép downstream đọc lại (body cần cho cả HMAC hash lẫn @RequestBody). */
public class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    public byte[] getBody() { return body; }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            public int read() { return bais.read(); }
            public boolean isFinished() { return bais.available() == 0; }
            public boolean isReady() { return true; }
            public void setReadListener(ReadListener l) {}
        };
    }
}
