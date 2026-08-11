package com.fieldservice.platform.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Request wrapper that replays a pre-read body bytes array through {@link #getInputStream()}.
 *
 * <p>Required because {@link IdempotencyFilter} reads the full request body before the filter
 * chain executes (to hash it for the claim). Without this wrapper downstream handlers would
 * receive an exhausted stream. The wrapper replays the bytes transparently.
 */
class PreReadRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    PreReadRequestWrapper(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished()                  { return bais.available() == 0; }
            @Override public boolean isReady()                     { return true; }
            @Override public void setReadListener(ReadListener l)  {}
            @Override public int read() throws IOException         { return bais.read(); }
            @Override public int read(byte[] b, int off, int len)  { return bais.read(b, off, len); }
        };
    }
}
