package com.payments.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
public class DatabaseConcurrencyFilter extends OncePerRequestFilter {

    private final Semaphore permits;
    private final long permitTimeoutMs;
    private final Counter rejectedRequests;
    private final Counter interruptedRequests;

    public DatabaseConcurrencyFilter(PaymentsProperties properties, MeterRegistry meterRegistry) {
        this.permits = new Semaphore(properties.getHttp().getDbPermits(), true);
        this.permitTimeoutMs = properties.getHttp().getDbPermitTimeoutMs();
        this.rejectedRequests = Counter.builder("payments.http.db_permits.rejected")
                .description("HTTP requests rejected because no database concurrency permit was available in time")
                .register(meterRegistry);
        this.interruptedRequests = Counter.builder("payments.http.db_permits.interrupted")
                .description("HTTP requests interrupted while waiting for a database concurrency permit")
                .register(meterRegistry);
        Gauge.builder("payments.http.db_permits.available", permits, Semaphore::availablePermits)
                .description("Current number of available database concurrency permits")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/health".equals(path) || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(permitTimeoutMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                rejectedRequests.increment();
                writeServiceUnavailable(response, "database concurrency limit reached");
                return;
            }
            filterChain.doFilter(request, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptedRequests.increment();
            writeServiceUnavailable(response, "request interrupted");
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    private void writeServiceUnavailable(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("""
                {"code":"service_unavailable","message":"%s"}
                """.formatted(message));
    }
}
