package com.payments.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.Semaphore;

@Component
public class DatabaseConcurrencyFilter extends OncePerRequestFilter {

    private final Semaphore permits;

    public DatabaseConcurrencyFilter(@Value("${payments.http.db-permits:24}") int dbPermits) {
        this.permits = new Semaphore(dbPermits, true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean acquired = false;
        try {
            permits.acquire();
            acquired = true;
            filterChain.doFilter(request, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "request interrupted");
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }
}
