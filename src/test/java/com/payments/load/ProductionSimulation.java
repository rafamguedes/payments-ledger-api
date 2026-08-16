package com.payments.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.tryMax;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class ProductionSimulation extends Simulation {

    private static final String BASE_URL = PixLoadSupport.baseUrl();
    private static final int ACCOUNTS = PixLoadSupport.intProperty("accounts", 200);
    private static final int SEED_TRANSFERS = PixLoadSupport.intProperty("seedTransfers", 500);
    private static final long SEED_BALANCE = PixLoadSupport.longProperty("seedBalance", 1_000_000_000L);
    private static final double TARGET_RPS = Double.parseDouble(System.getProperty("targetRps", "120"));
    private static final int RAMP_SECONDS = PixLoadSupport.intProperty("rampSeconds", 60);
    private static final int DURATION_SECONDS = PixLoadSupport.intProperty("durationSeconds", 300);
    private static final int RETRY_ATTEMPTS = PixLoadSupport.intProperty("retryAttempts", 3);
    private static final int RETRY_PAUSE_MS = PixLoadSupport.intProperty("retryPauseMs", 200);

    private static final List<String> accountIds = new CopyOnWriteArrayList<>();
    private static final List<String> transferIds = new CopyOnWriteArrayList<>();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ChainBuilder postTransfer = feed(PixLoadSupport.transferFeeder(accountIds))
            .exec(tryMax(RETRY_ATTEMPTS).on(
                    exec(http("POST /transfers")
                            .post("/transfers")
                            .body(StringBody("""
                            {"payerId":"#{payerId}","payeeId":"#{payeeId}","amount":#{amount},"idempotencyKey":"#{idempotencyKey}"}
                            """))
                            .check(status().in(200, 201))),
                    pause(Duration.ofMillis(RETRY_PAUSE_MS))
            ));

    private final ChainBuilder getStatement = feed(PixLoadSupport.statementFeeder(accountIds))
            .exec(tryMax(RETRY_ATTEMPTS).on(
                    exec(http("GET /accounts/{id}/statement")
                            .get("/accounts/#{accountId}/statement?limit=50")
                            .check(status().is(200))),
                    pause(Duration.ofMillis(RETRY_PAUSE_MS))
            ));

    private final ChainBuilder getTransfer = feed(PixLoadSupport.transferReadFeeder(transferIds))
            .exec(tryMax(RETRY_ATTEMPTS).on(
                    exec(http("GET /transfers/{id}")
                            .get("/transfers/#{transferId}")
                            .check(status().is(200))),
                    pause(Duration.ofMillis(RETRY_PAUSE_MS))
            ));

    private final ScenarioBuilder transfers = scenario("production transfers").exec(postTransfer);
    private final ScenarioBuilder statements = scenario("production statements").exec(getStatement);
    private final ScenarioBuilder transferReads = scenario("production transfer reads").exec(getTransfer);

    @Override
    public void before() {
        accountIds.addAll(PixLoadSupport.createAccounts(BASE_URL, "gat-prod", ACCOUNTS, SEED_BALANCE));
        transferIds.addAll(PixLoadSupport.createTransfers(BASE_URL, accountIds, SEED_TRANSFERS));
    }

    {
        setUp(
                transfers.injectOpen(
                        rampUsersPerSec(1).to(TARGET_RPS * 0.70).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantUsersPerSec(TARGET_RPS * 0.70).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                statements.injectOpen(
                        rampUsersPerSec(1).to(TARGET_RPS * 0.20).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantUsersPerSec(TARGET_RPS * 0.20).during(Duration.ofSeconds(DURATION_SECONDS))
                ),
                transferReads.injectOpen(
                        rampUsersPerSec(1).to(TARGET_RPS * 0.10).during(Duration.ofSeconds(RAMP_SECONDS)),
                        constantUsersPerSec(TARGET_RPS * 0.10).during(Duration.ofSeconds(DURATION_SECONDS))
                )
        ).protocols(httpProtocol)
                .assertions(global().failedRequests().count().is(0L));
    }
}
