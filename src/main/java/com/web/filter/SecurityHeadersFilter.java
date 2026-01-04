package com.web.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;

import org.jboss.logging.Logger;

@Provider
public class SecurityHeadersFilter implements ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(SecurityHeadersFilter.class);

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        String origin = requestContext.getHeaderString("Origin");
        LOG.info("SecurityHeadersFilter executing. Origin: " + origin + ", Path: "
                + requestContext.getUriInfo().getPath());

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

        if (!responseContext.getHeaders().containsKey("Access-Control-Allow-Origin")) {
            if (origin != null && (origin.equals("https://pizzaexpressdemo.netlify.app")
                    || origin.startsWith("http://localhost"))) {
                responseContext.getHeaders().add("Access-Control-Allow-Origin", origin);
                responseContext.getHeaders().add("Access-Control-Allow-Credentials", "true");

                if (!responseContext.getHeaders().containsKey("Access-Control-Expose-Headers")) {
                    responseContext.getHeaders().add("Access-Control-Expose-Headers",
                            "x-xsrf-token, X-XSRF-TOKEN, x-csrf-token, X-CSRF-TOKEN");
                }
            }
        }

        if ("OPTIONS".equalsIgnoreCase(requestContext.getMethod())) {
            if (!responseContext.getHeaders().containsKey("Access-Control-Allow-Headers")) {
                responseContext.getHeaders().add("Access-Control-Allow-Headers",
                        "accept, authorization, content-type, x-requested-with, x-xsrf-token, X-XSRF-TOKEN, x-csrf-token, X-CSRF-TOKEN");
            }
            if (!responseContext.getHeaders().containsKey("Access-Control-Allow-Methods")) {
                responseContext.getHeaders().add("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS");
            }
            if (!responseContext.getHeaders().containsKey("Access-Control-Allow-Credentials")) {
                responseContext.getHeaders().add("Access-Control-Allow-Credentials", "true");
            }
        }
    }
}
