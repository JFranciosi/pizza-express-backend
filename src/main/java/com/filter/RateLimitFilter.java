package com.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Provider
public class RateLimitFilter implements ContainerRequestFilter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String ip = requestContext.getHeaderString("X-Forwarded-For");
        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        } else {
            ip = ip.split(",")[0].trim();
        }

        Bucket bucket = buckets.computeIfAbsent(ip, k -> createNewBucket());

        if (!bucket.tryConsume(1)) {
            requestContext.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("Too many requests").build());
        }
    }

    private Bucket createNewBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(20)
                .refillGreedy(20, Duration.ofSeconds(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
