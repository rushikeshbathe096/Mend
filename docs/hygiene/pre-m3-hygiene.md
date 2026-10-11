# Pre-M3 hygiene

Branch `hygiene/pre-m3`, cut from the tip of `m2/execution-safety` (`1eadef1`). `main` does not contain M2 yet, and the ≥ 647 test bar needs M2. Rebase onto `main` after M2 is merged. Not merged, not pushed.

## Problem 1 — tests wrote into the dev database

**Finding (before):** `application-test.properties` used `jdbc:postgresql://localhost:5433/mend`, the same database the dev app uses (`application.properties`: `${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5433/mend}`). The tests also ran the dev seed (`mend.dev.seed=true`). `AbstractIntegrationTest` set nothing, and all 24 `@SpringBootTest` classes used that URL.

**Approach:** (a) in spirit, implemented as a JUnit Jupiter extension instead of a `LauncherSessionListener`. The listener interface lives in `junit-platform-launcher`, which is not on the test compile classpath: Surefire adds it at runtime only. Using it would mean a new Maven dependency. The Jupiter root `ExtensionContext.Store` gives the same once-per-run lifecycle with `junit-jupiter-api`, which is already present.
- `com.mend.testsupport.IsolatedTestDatabase` is auto-registered for every test class (`META-INF/services/org.junit.jupiter.api.extension.Extension` + `junit-platform.properties`: `junit.jupiter.extensions.autodetection.enabled=true`).
- **Start:** before the first test class it drops its own stale databases. Only names matching `^mend_test_\d{10}_[0-9a-f]{8}$` whose timestamp is older than 24 h are dropped; `mend`, the pre-existing `mend_test` and other names are never touched. It then creates `mend_test_<epoch>_<random>`.
- **Publishing:** the URL goes out as `mend.test.datasource.url`, together with `spring.datasource.url/username/password` and `spring.flyway.url/user/password` as system properties. These outrank environment variables and profile files, so an exported `SPRING_DATASOURCE_URL` (e.g. from `.env`) or a test without the `test` profile still cannot reach `mend`.
- **Migration:** Flyway migrates the new database from V1 when the first Spring context starts.
- **End:** when the root store closes, it runs `DROP DATABASE … WITH (FORCE)`. FORCE is needed because cached Spring contexts still hold pooled connections until JVM exit; Spring logs a few reconnect errors after the drop. The admin connection is held for the whole run so the drop never needs a new connection slot.
- `application-test.properties`: `spring.datasource.url=${mend.test.datasource.url}`, with **no fallback** (a missed setup fails instead of using the dev database). Hikari `minimum-idle=2`, `idle-timeout=10000` per cached context (max pool stays 10).
- **Postgres down:** the extension logs and continues, so pure unit tests run and Spring tests fail closed.
- **Server override:** `-Dmend.test.db.server-url=jdbc:postgresql://host:port/postgres`, `mend.test.db.user/password`, or `MEND_TEST_DB_*` env vars.
- The "park other rows" helpers were kept: classes within one run still share that run's database.

## Problem 2 — demo controller active in every profile

- `DemoController` has `@ConditionalOnProperty(name = "mend.demo.enabled", havingValue = "true")`: `false` in `application.properties`, `true` in `application-dev.properties`, `@TestPropertySource` on `Phase19MerchantProductIntegrationTest` (the only test that uses it).
- Every fabricated step is labelled `SIMULATED`: webhooks, classification, agent decisions, the customer's payment, the provider timeout, the duplicate delivery and the AI timeout. The labels are in the narration, the stored transition/audit reasons, the agent-decision text, the response `message` and the scenario catalog, and the response has `simulated: true`.
- Provider steps say whether the provider is the mock (SIMULATED, no request) or Razorpay test mode (a real request).
- The duplicate-event narration no longer claims an interception: it was a lookup.
- Fabricated webhook rows are stored with `source = MEND_DEMO_SIMULATED`, not `RAZORPAY` (invariant 3).
- Unknown routes returned **500** ("No static resource …"); `NoResourceFoundException` now maps to 404.
- **Authentication:** `/api/v1/demo/**` is not a public path in `SecurityFilter`. It requires a valid JWT, an ACTIVE user, and membership of the `X-Merchant-Id` merchant. There is **no role check**: any member can run scenarios, which create campaigns and fake RECOVERED outcomes. Reported, not redesigned.
- **Out-of-the-box profile:** `spring.profiles.active=${SPRING_PROFILES_ACTIVE:dev}` in `application.properties`. Starting the app with no profile setting activates **dev**, so the demo is **on** by default. Not changed here; it also controls the dev seed.

## Evidence (copied from output)

1. Two consecutive full runs (`cd backend && ./mvnw test`), after the review fixes:
   - run 1: `[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` — [IsolatedTestDatabase] created mend_test_1791717359_d0eb78c2;[IsolatedTestDatabase] dropped mend_test_1791717359_d0eb78c2;
   - run 2: `[INFO] Tests run: 654, Failures: 0, Errors: 0, Skipped: 0` — [IsolatedTestDatabase] created mend_test_1791717416_a3a953af;[IsolatedTestDatabase] dropped mend_test_1791717416_a3a953af;
2. Databases on the server after the runs (`select datname from pg_database`): `mend`, `mend_test`, `postgres`, `template0`, `template1`. No `mend_test_*` database remains; `mend_test` predates this work and does not match the pattern.
3. Dev database row counts around the two runs:

   | table | before | after |
   |---|---|---|
   | action_intents | 551 | 551 |
   | campaigns | 603 | 603 |
   | audit_logs | 8751 | 8751 |

   An earlier pair of runs (before the review fixes, 652 tests each) also left the dev counts unchanged at 61 / 74 / 6670.
   **Incident:** the jump from 61 / 74 / 6670 to 551 / 603 / 8751 happened during review verification. A deliberately mutated build, with the new override removed and `SPRING_DATASOURCE_URL` pointing at `mend`, proved that `PostgresConnectivityTest` fails without the fix. That run's Spring context started on the dev database and the dev seed loaded `MEND_DEMO_DATASET_V1` there. The rows were not deleted; that is the owner's decision.
4. App start (jar, throwaway database `mend_appcheck_hygiene`, dropped afterwards; token from `/auth/bootstrap` + `/auth/login`):

   | profile | GET /api/v1/demo/scenarios without token | with token | POST /trigger-scenario with token |
   |---|---|---|---|
   | `--spring.profiles.active=default` | 401 | **404** | 404 |
   | `--spring.profiles.active=dev` | 401 | **200** (descriptions start with "SIMULATED:") | 200 |

   Without a token the auth filter answers 401 before routing, in both profiles.
   Exported dev URL check: `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/mend SPRING_PROFILES_ACTIVE=dev ./mvnw test -Dtest=PostgresConnectivityTest` passes, and the test asserts `current_database()` matches `mend_test_*`.

## Tests added or changed
- New:
  - `IsolatedTestDatabaseTest` (3): stale-name rule; URL with query parameters; the run's URL is a `mend_test_*` database.
  - `DemoControllerGateTest` (2): no bean by default; 404 through MVC.
  - Phase19 `demoNarrationLabelsEverySimulatedStep` (all six scenarios plus the catalog) and `demoRequiresAuthentication`.
- Strengthened: `PostgresConnectivityTest`. It connected to `localhost:5432/mend` and swallowed every exception, so it could not fail; it now queries through the real DataSource and asserts the isolated database.
- No test deleted or weakened.
