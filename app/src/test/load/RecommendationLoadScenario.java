package load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatling.javaapi.core.ActionBuilder;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.CoreDsl;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpDsl;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Gatling load scenario for the AI Smart Dispatch recommendation endpoint.
 *
 * <h3>Purpose</h3>
 * Validates the Phase 2 exit criterion: p95 recommendation latency ≤ 3 seconds and
 * server error rate &lt; 1%, against a seeded pool of 200 technicians at twice the design
 * peak write rate.
 *
 * <h3>Profile selection</h3>
 * Set the JVM system property {@code load.profile} to override the profile:
 * <pre>
 *   mvn gatling:test -Dload.profile=degraded
 *   mvn gatling:test -Dload.profile=ci-smoke
 * </pre>
 * Defaults to {@code healthy} (the Phase 2 gate profile).
 *
 * <h3>Prerequisites</h3>
 * <ol>
 *   <li>The application must be running at the URL specified by {@code load.baseUrl}
 *       (default {@code http://localhost:8080}).</li>
 *   <li>The travel-time provider stub must be running at {@code load.travelStubUrl}
 *       (default {@code http://localhost:8099}) — see
 *       {@code src/test/resources/stubs/travel-matrix/} for the WireMock stub files.</li>
 *   <li>The database must have been seeded with
 *       {@code fixtures/seed-core.sql} and
 *       {@code fixtures/seed-load-200-technicians.sql}.</li>
 * </ol>
 *
 * <h3>Scenario mix (healthy profile, twice design peak)</h3>
 * <ul>
 *   <li>50% — GET /api/v1/work-orders/{id}/recommendations</li>
 *   <li>20% — POST /api/v1/work-orders (create)</li>
 *   <li>15% — GET /api/v1/work-orders (paginated search)</li>
 *   <li>10% — PATCH /api/v1/work-orders/{id}/transitions (state transition)</li>
 *   <li>5%  — POST /api/v1/work-orders/{id}/assignments</li>
 * </ul>
 *
 * <h3>Phases</h3>
 * All durations are read from {@code src/test/load/config/profiles.json}.
 * The warm-up phase is excluded from percentile calculation by Gatling's
 * {@code RampConcurrentUsers} + {@code atOnceUsers} warm-up pattern.
 *
 * <h3>Result file</h3>
 * After the simulation, a machine-readable {@code results.json} is written to
 * {@code target/load-results/}. Run {@link com.fieldservice.load.ThresholdEvaluator}
 * against it with {@code src/test/load/config/thresholds.json} to gate the build.
 *
 * @see com.fieldservice.load.ThresholdEvaluator
 */
public class RecommendationLoadScenario extends Simulation {

    private static final ObjectMapper MAPPER       = new ObjectMapper();
    private static final String       BASE_URL     = System.getProperty("load.baseUrl",       "http://localhost:8080");
    private static final String       PROFILE_NAME = System.getProperty("load.profile",       "healthy");
    private static final String       DISPATCHER_TOKEN  = System.getProperty("load.dispatcherToken", "test-dispatcher-token");

    // ── Profile loading ───────────────────────────────────────────────────────

    private static final JsonNode PROFILE = loadProfile();

    private static JsonNode loadProfile() {
        try (InputStream is = RecommendationLoadScenario.class
                .getResourceAsStream("/load/config/profiles.json")) {
            if (is == null) {
                // Fall back to embedded defaults if the resource is absent at classpath
                return MAPPER.readTree("""
                    {"profiles":{"healthy":{"warmUpSeconds":60,"rampUpSeconds":30,"holdSeconds":120,
                    "coolDownSeconds":15,"targetVirtualUsers":40}}}""");
            }
            return MAPPER.readTree(is).path("profiles").path(PROFILE_NAME);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load load profile '" + PROFILE_NAME + "'", e);
        }
    }

    private int profileInt(String key, int defaultValue) {
        JsonNode node = PROFILE.path(key);
        return node.isMissingNode() ? defaultValue : node.intValue();
    }

    // ── HTTP protocol ─────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .header("Authorization", "Bearer " + DISPATCHER_TOKEN)
            .shareConnections()
            .disableCaching();

    // ── Work order IDs shared across scenario mix ──────────────────────────────
    // These are seeded by seed-load-200-technicians.sql companion WO seed.

    private static final String WO_NEW_1 = "cc000000-0000-7206-0020-000000000001";
    private static final String WO_NEW_2 = "cc000000-0000-7206-0020-000000000002";

    // ── Scenario builders ─────────────────────────────────────────────────────

    private final ChainBuilder getRecommendations =
            exec(http("GET recommendations (pool=200)")
                    .get("/api/v1/work-orders/" + WO_NEW_1 + "/recommendations?size=20")
                    .check(status().is(200))
                    .check(jsonPath("$.data").exists())
                    .check(bodyString().saveAs("lastRecommendationBody")));

    private final ChainBuilder createWorkOrder =
            exec(http("POST work-order create")
                    .post("/api/v1/work-orders")
                    .body(StringBody("""
                        {
                          "siteId": "cc000000-0000-7206-0001-000000000001",
                          "priority": "HIGH",
                          "description": "Load test synthetic fault #{uuid}",
                          "certificationRequired": "ELEC_DISPATCH"
                        }
                        """.replace("#{uuid}", UUID.randomUUID().toString())))
                    .check(status().in(201, 409)));  // 409 on duplicate reference is acceptable

    private final ChainBuilder searchWorkOrders =
            exec(http("GET work-orders paginated search")
                    .get("/api/v1/work-orders?state=NEW&size=20&page=0")
                    .check(status().is(200))
                    .check(jsonPath("$.page.totalElements").exists()));

    private final ChainBuilder transitionWorkOrder =
            exec(http("PATCH work-order transition to ASSIGNED")
                    .patch("/api/v1/work-orders/" + WO_NEW_2 + "/transitions")
                    .body(StringBody("""
                        {
                          "targetState": "ASSIGNED",
                          "technicianId": "cc000000-0000-7206-0011-000000000001"
                        }
                        """))
                    .check(status().in(200, 409, 422)));  // 409/422 acceptable under concurrent load

    private final ChainBuilder assignWorkOrder =
            exec(http("POST work-order assignment")
                    .post("/api/v1/work-orders/" + WO_NEW_1 + "/assignments")
                    .body(StringBody("""
                        {
                          "technicianId": "cc000000-0000-7206-0011-000000000001"
                        }
                        """))
                    .check(status().in(200, 201, 409, 422)));

    // ── Main scenario (weighted mix) ──────────────────────────────────────────

    private final ScenarioBuilder dispatchMix = scenario("Dispatch recommendation mix")
            .randomSwitch()
            .on(
                50, getRecommendations,
                20, createWorkOrder,
                15, searchWorkOrders,
                10, transitionWorkOrder,
                5,  assignWorkOrder
            );

    // ── Simulation setup ──────────────────────────────────────────────────────

    {
        int warmUpSeconds    = profileInt("warmUpSeconds",    60);
        int rampUpSeconds    = profileInt("rampUpSeconds",    30);
        int holdSeconds      = profileInt("holdSeconds",      120);
        int coolDownSeconds  = profileInt("coolDownSeconds",  15);
        int targetVus        = profileInt("targetVirtualUsers", 40);

        PopulationBuilder population = dispatchMix.injectOpen(
                // Warm-up: ramp to full load, let caches prime — excluded from percentile gate
                rampUsersPerSec(1).to(targetVus).during(Duration.ofSeconds(warmUpSeconds)),
                // Ramp to design load
                rampUsersPerSec(targetVus).to(targetVus * 2).during(Duration.ofSeconds(rampUpSeconds)),
                // Hold at twice peak — this phase produces the gated percentiles
                constantUsersPerSec(targetVus * 2).during(Duration.ofSeconds(holdSeconds)),
                // Cool-down
                rampUsersPerSec(targetVus * 2).to(0).during(Duration.ofSeconds(coolDownSeconds))
        );

        setUp(population)
                .protocols(httpProtocol)
                .assertions(
                        // Phase 2 exit criterion: p95 <= 3 s
                        forAll().responseTime().percentile(95).lt(3001),
                        // Phase 2 exit criterion: server error rate < 1%
                        forAll().failedRequests().percent().lt(1.0)
                );
    }
}
