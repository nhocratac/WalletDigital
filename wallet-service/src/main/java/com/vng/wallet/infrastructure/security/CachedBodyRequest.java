package com.vng.wallet.infrastructure.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Đọc body một lần (có chặn kích thước), cho phép downstream đọc lại — body cần cho cả HMAC
 * hash lẫn {@code @RequestBody} của controller. Soi gương kyc {@code CachedBodyRequest}.
 */
public class CachedBodyRequest extends HttpServletRequestWrapper {

    /** Body vượt quá giới hạn cho phép — caller dịch thành 413. */
    public static class BodyTooLargeException extends IOException {}

    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request, long maxBytes) throws IOException {
        super(request);
        byte[] data = request.getInputStream().readNBytes((int) maxBytes + 1);
        if (data.length > maxBytes) throw new BodyTooLargeException();
        this.body = data;
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

    @Override
    public BufferedReader getReader() {
        Charset cs = StandardCharsets.UTF_8;
        String enc = getCharacterEncoding();
        if (enc != null) {
            try { cs = Charset.forName(enc); } catch (Exception ignored) {}
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), cs));
    }
}
