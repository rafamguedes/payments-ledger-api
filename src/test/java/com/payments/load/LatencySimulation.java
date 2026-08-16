package com.payments.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

public class LatencySimulation extends Simulation {

    private static final String BASE_URL = PixLoadSupport.baseUrl();
    private static final int ACCOUNTS = PixLoadSupport.intProperty("accounts", 50);
    private static final int SEED_TRANSFERS = PixLoadSupport.intProperty("seedTransfers", 50);
    private static final long SEED_BALANCE = PixLoadSupport.longProperty("seedBalance", 100_000_000L);
    private static final int CONCURRENT_USERS = PixLoadSupport.intProperty("concurrentUsers", 50);

    private static final List<String> accountIds = new CopyOnWriteArrayList<>();
    private static final List<String> transferIds = new CopyOnWriteArrayList<>();

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

    private final ChainBuilder getTransfer = feed(PixLoadSupport.transferReadFeeder(transferIds))
            .exec(http("GET /transfers/{id}")
                    .get("/transfers/#{transferId}")
                    .check(status().is(200)));

    private final ScenarioBuilder transfers = scenario("latency transfers").exec(postTransfer);
    private final ScenarioBuilder statements = scenario("latency statements").exec(getStatement);
    private final ScenarioBuilder transferReads = scenario("latency transfer reads").exec(getTransfer);

    @Override
    public void before() {
        accountIds.addAll(PixLoadSupport.createAccounts(BASE_URL, "gat-lat", ACCOUNTS, SEED_BALANCE));
        transferIds.addAll(PixLoadSupport.createTransfers(BASE_URL, accountIds, SEED_TRANSFERS));
    }

    {
        setUp(
                transfers.injectClosed(constantConcurrentUsers(Math.max(1, (int) (CONCURRENT_USERS * 0.60)))
                        .during(Duration.ofSeconds(30))),
                statements.injectClosed(constantConcurrentUsers(Math.max(1, (int) (CONCURRENT_USERS * 0.25)))
                        .during(Duration.ofSeconds(30))),
                transferReads.injectClosed(constantConcurrentUsers(Math.max(1, CONCURRENT_USERS
                        - (int) (CONCURRENT_USERS * 0.60)
                        - (int) (CONCURRENT_USERS * 0.25)))
                        .during(Duration.ofSeconds(30)))
        ).protocols(httpProtocol);
    }
}
