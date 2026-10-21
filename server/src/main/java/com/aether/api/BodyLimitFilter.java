package com.aether.api;

import java.io.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON and raw webhook bodies, including chunked requests, before deserialization. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodyLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!request.getRequestURI().startsWith("/api/")||!(request.getMethod().equals("POST")||request.getMethod().equals("PUT"))) {
            chain.doFilter(request,response); return;
        }
        int limit=request.getRequestURI().equals("/api/stripe/webhook")?1048576:32768;
        if(request.getContentLengthLong()>limit) { response.sendError(413); return; }
        byte[] body=request.getInputStream().readNBytes(limit+1);
        if(body.length>limit) { response.sendError(413); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public boolean isFinished() { return input.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous API only"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
        },response);
    }
}
