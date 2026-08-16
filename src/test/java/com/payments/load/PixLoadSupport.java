package com.payments.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class PixLoadSupport {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN_ID = Long.toString(System.currentTimeMillis(), 36);

    private PixLoadSupport() {
    }

    static String baseUrl() {
        String fromProperty = System.getProperty("baseUrl");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }

        String fromEnv = System.getenv("BASE_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }

        return "http://localhost:3005";
    }

    static int intProperty(String name, int defaultValue) {
        return Integer.getInteger(name, defaultValue);
    }

    static long longProperty(String name, long defaultValue) {
        return Long.getLong(name, defaultValue);
    }

    static List<String> createAccounts(String baseUrl, String prefix, int count, long seedBalance) {
        waitForHealth(baseUrl);

        List<String> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = "%s-%s-%03d".formatted(prefix, RUN_ID, i);
            HttpResponse<String> response = postJson(
                    baseUrl,
                    "/accounts",
                    """
                    {"id":"%s","balance":%d}
                    """.formatted(id, seedBalance)
            );

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                ids.add(id);
            } else {
                throw new IllegalStateException("Could not create account %s: HTTP %d %s"
                        .formatted(id, response.statusCode(), response.body()));
            }
        }
        return List.copyOf(ids);
    }

    static List<String> createTransfers(String baseUrl, List<String> accountIds, int count) {
        List<String> transferIds = new ArrayList<>(count);
        if (accountIds.size() < 2) {
            return transferIds;
        }

        for (int i = 0; i < count; i++) {
            String payerId = accountIds.get(i % accountIds.size());
            String payeeId = accountIds.get((i + 1) % accountIds.size());
            HttpResponse<String> response = postJson(
                    baseUrl,
                    "/transfers",
                    """
                    {"payerId":"%s","payeeId":"%s","amount":500,"idempotencyKey":"seed-%s-%03d"}
                    """.formatted(payerId, payeeId, RUN_ID, i)
            );

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                transferIds.add(readId(response.body()));
            } else {
                throw new IllegalStateException("Could not create seed transfer: HTTP %d %s"
                        .formatted(response.statusCode(), response.body()));
            }
        }
        return List.copyOf(transferIds);
    }

    static Iterator<Map<String, Object>> transferFeeder(List<String> accountIds) {
        return new EndlessIterator(() -> {
            ensureAccounts(accountIds, 2);

            ThreadLocalRandom random = ThreadLocalRandom.current();
            int payerIndex = random.nextInt(accountIds.size());
            int payeeIndex = random.nextInt(accountIds.size());
            if (payerIndex == payeeIndex) {
                payeeIndex = (payeeIndex + 1) % accountIds.size();
            }

            return Map.of(
                    "payerId", accountIds.get(payerIndex),
                    "payeeId", accountIds.get(payeeIndex),
                    "amount", random.nextLong(100, 1_000),
                    "idempotencyKey", "load-" + UUID.randomUUID()
            );
        });
    }

    static Iterator<Map<String, Object>> statementFeeder(List<String> accountIds) {
        return new EndlessIterator(() -> {
            ensureAccounts(accountIds, 1);
            return Map.of("accountId", pick(accountIds));
        });
    }

    static Iterator<Map<String, Object>> transferReadFeeder(List<String> transferIds) {
        return new EndlessIterator(() -> {
            if (transferIds.isEmpty()) {
                throw new IllegalStateException("No seed transfers available");
            }
            return Map.of("transferId", pick(transferIds));
        });
    }

    private static void waitForHealth(String baseUrl) {
        URI uri = URI.create(baseUrl + "/health");
        for (int attempt = 1; attempt <= 30; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return;
                }
            } catch (IOException e) {
                sleep();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for health check", e);
            }
            sleep();
        }

        throw new IllegalStateException("Application is not healthy at " + uri);
    }

    private static HttpResponse<String> postJson(String baseUrl, String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP request interrupted", e);
        }
    }

    private static String readId(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            return node.path("id").asText();
        } catch (IOException e) {
            throw new IllegalStateException("Could not parse transfer response: " + body, e);
        }
    }

    private static void ensureAccounts(List<String> accountIds, int minimum) {
        if (accountIds.size() < minimum) {
            throw new IllegalStateException("Expected at least %d seeded accounts".formatted(minimum));
        }
    }

    private static String pick(List<String> values) {
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private static void sleep() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sleeping", e);
        }
    }

    private record EndlessIterator(FeederRow nextRow) implements Iterator<Map<String, Object>> {
        @Override
        public boolean hasNext() {
            return true;
        }

        @Override
        public Map<String, Object> next() {
            return nextRow.get();
        }
    }

    @FunctionalInterface
    private interface FeederRow {
        Map<String, Object> get();
    }
}
