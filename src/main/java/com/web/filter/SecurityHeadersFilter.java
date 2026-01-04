package com.web.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        responseContext.getHeaders().add("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        String csp = "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                "img-src 'self' data: https:; " +
                "font-src 'self' data: https: https://fonts.gstatic.com; " +
                "connect-src 'self' https: wss:; " +
                "frame-ancestors 'none';";

        responseContext.getHeaders().add("Content-Security-Policy", csp);
        responseContext.getHeaders().add("X-Frame-Options", "DENY");
        responseContext.getHeaders().add("X-Content-Type-Options", "nosniff");
        responseContext.getHeaders().add("Referrer-Policy", "strict-origin-when-cross-origin");
        responseContext.getHeaders().add("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
    }
}
