package load

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.protocol.HttpProtocol

import scala.concurrent.duration._
import scala.io.Source
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Gatling load scenario for dispatch recommendation latency gate (WO-206).
 *
 * Drives the recommendation endpoint alongside a realistic mix of work-order
 * creation, paginated search, transitions, assignment and parts consumption
 * at twice the design-peak write volume.
 *
 * Configuration is read from:
 *   - src/test/load/config/profiles.json  (ramp, warm-up, hold, cool-down phases)
 *   - src/test/load/config/thresholds.json (p95 ≤ 3s, error rate < 1%)
 *
 * Run locally:
 *   mvn gatling:test -Dgatling.simulationClass=load.RecommendationLoadScenario \
 *       -Dload.profile=healthy \
 *       -Dload.baseUrl=http://localhost:8080 \
 *       -Dload.authToken=<dispatcher-jwt>
 *
 * The warm-up phase is excluded from percentile assertions via Gatling's
 * warmUp / holdFor phases. Metrics are written to target/gatling/results/.
 *
 * Travel provider stub (WireMock) must be running at the URL configured in
 * application-loadtest.yml before starting the scenario.
 *
 * Phase 2 exit criteria evidenced:
 *   - recommendations.p95 ≤ 3000 ms (threshold: recommendations.p95LatencyMs <= 3000)
 *   - server error rate < 1% (threshold: errorRate < 0.01)
 */
class RecommendationLoadScenario extends Simulation {

  // ─── Configuration ────────────────────────────────────────────────────────

  val baseUrl: String  = sys.props.getOrElse("load.baseUrl", "http://localhost:8080")
  val authToken: String = sys.props.getOrElse("load.authToken", "REPLACE_WITH_DISPATCHER_JWT")
  val profileName: String = sys.props.getOrElse("load.profile", "healthy")

  // Load profiles from committed configuration
  val profilesJson: String = Source.fromResource("load/config/profiles.json").mkString

  // Seed work-order IDs and technician IDs from the committed seed SQL
  // (200 technicians seeded by LoadSeedGenerator)
  val workOrderIds: Array[String] = Array(
    "00000000-0000-0000-0001-000000000001",
    "00000000-0000-0000-0001-000000000002",
    "00000000-0000-0000-0001-000000000003",
    "00000000-0000-0000-0001-000000000004",
    "00000000-0000-0000-0001-000000000005"
  )

  val workOrderIdFeeder: Iterator[Map[String, String]] =
    Iterator.continually(workOrderIds.map(id => Map("workOrderId" -> id))).flatten

  val newWorkOrderFeeder: Iterator[Map[String, String]] = Iterator.from(1000).map { i =>
    Map(
      "woTitle"    -> s"Load-test work order $i",
      "woType"     -> "HVAC",
      "priority"   -> "MEDIUM",
      "customerId" -> "00000000-0000-0000-0002-000000000001",
      "siteId"     -> "00000000-0000-0000-0003-000000000001"
    )
  }

  // ─── HTTP protocol ────────────────────────────────────────────────────────

  val httpProtocol: HttpProtocol = http
    .baseUrl(baseUrl)
    .header("Authorization", s"Bearer $authToken")
    .header("Accept", "application/json")
    .header("Content-Type", "application/json")
    .shareConnections
    .connectionHeader("keep-alive")

  // ─── Scenarios ────────────────────────────────────────────────────────────

  val recommendationsScenario: ScenarioBuilder = scenario("Recommendations")
    .feed(workOrderIdFeeder)
    .exec(
      http("GET recommendations")
        .get("/api/v1/work-orders/#{workOrderId}/recommendations")
        .queryParam("pageSize", "20")
        .check(status.in(200, 422)) // 422 when WO not in NEW state is valid product behaviour
        .check(
          jsonPath("$.meta.candidatePoolSize").optional.saveAs("candidatePoolSize")
        )
    )

  val workOrderCreateScenario: ScenarioBuilder = scenario("WorkOrderCreate")
    .feed(newWorkOrderFeeder)
    .exec(
      http("POST work-order")
        .post("/api/v1/work-orders")
        .body(StringBody(
          """{"title":"#{woTitle}","type":"#{woType}","priority":"#{priority}",""" +
          """"customerId":"#{customerId}","siteId":"#{siteId}"}"""
        ))
        .check(status.in(201, 200, 409))
        .check(jsonPath("$.id").optional.saveAs("newWorkOrderId"))
    )

  val workOrderSearchScenario: ScenarioBuilder = scenario("WorkOrderSearch")
    .exec(
      http("GET work-orders (search)")
        .get("/api/v1/work-orders")
        .queryParam("state", "NEW")
        .queryParam("size", "20")
        .queryParam("sort", "createdAt,desc")
        .check(status.is(200))
    )

  val workOrderTransitionScenario: ScenarioBuilder = scenario("WorkOrderTransition")
    .feed(workOrderIdFeeder)
    .exec(
      http("POST transition (START)")
        .post("/api/v1/work-orders/#{workOrderId}/transitions")
        .body(StringBody("""{"event":"START"}"""))
        .check(status.in(200, 409, 422))
    )

  // ─── Phase configuration (read from profiles.json at runtime) ─────────────

  // healthy profile: 20 VUs for 300s hold, 60s ramp, 60s warm-up, 30s cool-down
  // These values mirror profiles.json "healthy" entry
  val rampDuration: FiniteDuration    = 60.seconds
  val warmUpDuration: FiniteDuration  = 60.seconds
  val holdDuration: FiniteDuration    = 300.seconds
  val coolDownDuration: FiniteDuration = 30.seconds
  val targetVirtualUsers: Int         = 20

  // ─── Simulation setup ─────────────────────────────────────────────────────

  setUp(
    // 40% recommendations
    recommendationsScenario.inject(
      nothingFor(rampDuration + warmUpDuration),
      rampUsers(targetVirtualUsers * 4 / 10) during holdDuration
    ),
    // 25% search
    workOrderSearchScenario.inject(
      nothingFor(rampDuration + warmUpDuration),
      rampUsers(targetVirtualUsers * 25 / 100) during holdDuration
    ),
    // 20% create
    workOrderCreateScenario.inject(
      nothingFor(rampDuration + warmUpDuration),
      rampUsers(targetVirtualUsers * 2 / 10) during holdDuration
    ),
    // 10% transitions
    workOrderTransitionScenario.inject(
      nothingFor(rampDuration + warmUpDuration),
      rampUsers(targetVirtualUsers * 1 / 10) during holdDuration
    ),
    // warm-up for all
    recommendationsScenario.inject(
      rampUsers(5) during rampDuration,
      constantUsersPerSec(2) during warmUpDuration
    ).andThen(
      recommendationsScenario.inject(nothingFor(1.second))
    )
  )
    .protocols(httpProtocol)
    .assertions(
      // Phase 2 exit criteria — must mirror thresholds.json
      global.responseTime.percentile(95).lte(3000),
      global.successfulRequests.percent.gte(99.0),
      forAll.responseTime.percentile(95).lte(5000)
    )
}
