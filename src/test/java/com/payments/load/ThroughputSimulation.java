package com.payments.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class ThroughputSimulation extends Simulation {

    private static final String BASE_URL = PixLoadSupport.baseUrl();
    private static final int ACCOUNTS = PixLoadSupport.intProperty("accounts", 50);
    private static final long SEED_BALANCE = PixLoadSupport.longProperty("seedBalance", 100_000_000L);
    private static final double TARGET_RPS = Double.parseDouble(System.getProperty("targetRps", "200"));

    private static final List<String> accountIds = new CopyOnWriteArrayList<>();

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json");

    private final ChainBuilder postTransfer = feed(PixLoadSupport.transferFeeder(accountIds))
            .exec(http("POST /transfers")
                    .post("/transfers")
                    .body(StringBody("""
                    {"payerId":"#{payerId}","payeeId":"#{payeeId}","amount":#{amount},"idempotencyKey":"#{idempotencyKey}"}
                    """))
                    .check(status().in(200, 201)));

    private final ChainBuilder getStatement = feed(PixLoadSupport.statementFeeder(accountIds))
            .exec(http("GET /accounts/{id}/statement")
                    .get("/accounts/#{accountId}/statement")
                    .check(status().is(200)));

    private final ScenarioBuilder transfers = scenario("throughput transfers").exec(postTransfer);
    private final ScenarioBuilder statements = scenario("throughput statements").exec(getStatement);

    @Override
    public void before() {
        accountIds.addAll(PixLoadSupport.createAccounts(BASE_URL, "gat-thr", ACCOUNTS, SEED_BALANCE));
    }

    {
        setUp(
                transfers.injectOpen(
                        rampUsersPerSec(1).to(TARGET_RPS * 0.70).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(TARGET_RPS * 0.70).during(Duration.ofSeconds(30))
                ),
                statements.injectOpen(
                        rampUsersPerSec(1).to(TARGET_RPS * 0.30).during(Duration.ofSeconds(10)),
                        constantUsersPerSec(TARGET_RPS * 0.30).during(Duration.ofSeconds(30))
                )
        ).protocols(httpProtocol);
    }
}
