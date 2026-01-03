package com.web.filter;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.UUID;

@Provider
public class CsrfFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    @Inject
    SecurityIdentity securityIdentity;

    @ConfigProperty(name = "app.frontend.url")
    String frontendUrl;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String method = requestContext.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return;
        }

        if (securityIdentity.isAnonymous()) {
            return;
        }

        String csrfHeader = requestContext.getHeaderString(CSRF_HEADER_NAME);
        Cookie csrfCookie = requestContext.getCookies().get(CSRF_COOKIE_NAME);

        if (csrfHeader == null || csrfCookie == null || !csrfHeader.equals(csrfCookie.getValue())) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("CSRF Validation Failed")
                    .build());
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        if (securityIdentity.isAnonymous()) {
            return;
        }

        if (requestContext.getCookies().containsKey(CSRF_COOKIE_NAME)) {
            return;
        }

        if (responseContext.getCookies().containsKey(CSRF_COOKIE_NAME)) {
            return;
        }

        String token = UUID.randomUUID().toString();
        boolean isSecure = frontendUrl != null && frontendUrl.startsWith("https");
        NewCookie.SameSite sameSite = isSecure ? NewCookie.SameSite.NONE : NewCookie.SameSite.LAX;

        NewCookie cookie = new NewCookie.Builder(CSRF_COOKIE_NAME)
                .value(token)
                .path("/")
                .httpOnly(false)
                .secure(isSecure)
                .sameSite(sameSite)
                .maxAge(-1)
                .build();

        responseContext.getHeaders().add("Set-Cookie", cookie);
        responseContext.getHeaders().add(CSRF_HEADER_NAME, token);
    }
}
