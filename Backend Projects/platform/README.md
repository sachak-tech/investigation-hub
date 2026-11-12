# Investigation Hub platform foundation

This directory holds the first four independently runnable backend services and one shared Java library. It is the Milestone 1 synthetic alert-to-case-to-playbook slice, not the complete ten-service platform.

| Service | Port | PostgreSQL schema | Swagger UI |
| --- | ---: | --- | --- |
| Policy | 18081 | `investigation_policy` | [Policy API](http://localhost:18081/swagger-ui.html) |
| Case | 18082 | `investigation_case` | [Case API](http://localhost:18082/swagger-ui.html) |
| Playbook | 18083 | `investigation_playbook` for product mappings, `investigation_flowable` for Flowable CMMN | [Playbook API](http://localhost:18083/swagger-ui.html) |
| Alert ingestion | 18084 | `investigation_alert` | [Alert API](http://localhost:18084/swagger-ui.html) |

For a visual view of a running product case, open [the Playbook monitor](http://localhost:18083/playbook-monitor.html) and paste the `caseId` returned by the Case API or `smoke.py`. The monitor reads the same live Flowable step state as `GET /v1/playbooks/cases/{caseId}` and refreshes every three seconds. Several steps may be active at once. It is a read-only development view; the investigator workspace and policy-defined step navigation are later UI work.

For its review-stage and decision history, open [the Case journey](http://localhost:18082/case-journey.html) with the same `caseId`. It refreshes every three seconds and links to that case's Playbook monitor. The stage map comes from the case's pinned policy version; the timeline comes from a durable, append-only case journey table. This is a read-only development view, not the planned Next.js investigator workspace.

To inspect the CMMN model itself, open [the deployed model diagram](http://localhost:18083/v1/playbooks/model/diagram). Its layout coordinates are in `playbook-service/src/main/resources/cases/investigation.cmmn`. This is a static diagram of the latest deployed definition; the case monitor above shows runtime state for a specific case.

`service-common` is a library for correlation IDs and transactional outbox writing; it is not another microservice. Each service has a Flyway migration, health/readiness endpoints, structured log correlation field, and generated OpenAPI 3.1 documentation. The synthetic policy is seeded from a packaged fixture and evaluated deterministically by Policy.

## Run locally

Requires a reachable PostgreSQL database, Java 25, Maven, `psql`, `curl`, `rg`, `lsof`, and Python 3.9+. Docker is not required. Defaults are `localhost:5432/postgres` with user `postgres`; set `IH_DB_HOST`, `IH_DB_PORT`, `IH_DB_NAME`, and `IH_DB_USER` for another database. The database user needs permission to create schemas. Set `JAVA_HOME` if Java 25 is not the default. The scripts create dedicated schemas; they do not drop existing schemas or tables.

```sh
cd "Investigation HUB/Backend Projects/platform" # from the parent of the cloned repository
read -s IH_DB_PASSWORD
export IH_DB_PASSWORD
bash scripts/run-local.sh --smoke
```

The command builds all modules, applies Flyway migrations, starts all four services, checks readiness and Swagger/OpenAPI, posts two unique synthetic alerts, verifies the case and Flowable revision, prints `PASS`, and stops the services. It writes service logs to `../../.cache/platform-logs/`.

To keep the services running for Swagger exploration, use `bash scripts/run-local.sh` without `--smoke`, then run `python3 smoke.py` in another terminal. Press Ctrl+C in the first terminal to stop the services. Do not rebuild jars in place while those jars are running.

## Additional demo policies and cases

Three synthetic policies are seeded into Policy on startup, one published policy per tenant:

| Tenant | Policy fixture | Case coverage | Grouping window |
| --- | --- | --- | ---: |
| `demo-bank` | [Baseline AML policy](policy-service/src/main/resources/policies/synthetic-policy.json) | Transaction and transaction plus KYC | 30 days |
| `demo-expanded-bank` | [Expanded AML policy](policy-service/src/main/resources/policies/expanded-aml-policy.json) | Transaction, KYC, sanctions, PEP, and selected combinations | 30 days |
| `demo-sanctions-bank` | [Sanctions policy](policy-service/src/main/resources/policies/sanctions-policy.json) | Sanctions, PEP, and sanctions plus PEP | 14 days |

Readable synthetic source policies are in [Expanded AML policy text](policy-service/src/main/resources/policies/expanded-aml-policy.md) and [Sanctions policy text](policy-service/src/main/resources/policies/sanctions-policy.md). The adjacent JSON documents are the manually prepared executable representation currently seeded into PostgreSQL; policy-text ingestion and approval are later work.

With all four services running, create six persistent demo cases and eleven alerts:

```sh
cd "Investigation HUB/Backend Projects/platform" # from the parent of the cloned repository
python3 demo_scenarios.py
```

The script is idempotent for its fixed synthetic source alert IDs. It verifies that alerts are linked to the intended cases and that Flowable activates exactly the required policy steps. It prints case IDs and monitor links and saves the result to `../../.cache/demo-scenarios.json`. Open a case in the [Playbook monitor](http://localhost:18083/playbook-monitor.html) to inspect the live step state. These fixtures demonstrate classification and step activation; case routing and closure can be exercised separately below. Agent execution, closed-case linkage, and policy-text ingestion and approval are still later implementation work.

To see task dependencies, run `python3 linked_scenarios.py` while the services are running. It creates four fresh cases and ten alerts: waiting and activated examples for each of two case types. In transaction-plus-KYC, combined risk review waits for both human reviews. In transaction-plus-KYC-plus-PEP, initial human and agent steps run in parallel, combined risk waits for transaction and KYC reviews, and cross-signal risk waits for combined risk and PEP reviews. The script completes synthetic human tasks where needed and checks each state transition, then writes `../../.cache/linked-scenarios.json` with monitor links. The local runner enables `/v1/playbooks/demo/cases/{caseId}/tasks/{taskId}/complete` only for synthetic `demo-*` tenants; this development action is disabled by default outside the local runner. It does not replace the planned authenticated task-completion and evidence workflow. Existing cases retain the CMMN definition version with which they started, so use the newly created case IDs to inspect the linked behavior.

## Case review lifecycle

Playbook steps are investigation work tracked by Flowable. Case actions are review decisions tracked by Case. A case starts at the first `reviewStages` entry of its pinned policy version. The current stage and assignee appear in `GET /v1/cases/{caseId}`. `GET /v1/cases/{caseId}/available-actions` returns the actions and target stages permitted by that policy at the current stage, with an `enabled` flag and reason for a blocked closure. The caller supplies the development-only `X-Actor-Id` header.

`POST /v1/cases/{caseId}/actions` accepts `ESCALATE`, `RETURN_FOR_REWORK`, or `APPROVE_CLOSE`, with an action UUID, expected case revision, reason, and target stage or assignee for a handoff. Case records every accepted decision in `GET /v1/cases/{caseId}/actions`. Routing advances the case revision but does not complete a playbook step; playbook work may continue at the new review stage. Completing the playbook also does not close the case automatically. `APPROVE_CLOSE` is enabled only at the policy's human approval stage, after all required playbook steps have completed, and by an actor different from the preceding reviewer when the policy requires an independent check. Closing Case emits an event that closes the associated Flowable case. A new alert can change case type and required steps; closure checks the latest required step set. The current review stage stays in place until an explicit case action routes it.

`GET /v1/cases/{caseId}/journey` returns one revisioned view of the current stage, assignee, pinned policy stage map, and ordered case events: creation, alert links, classification changes, escalations, returns, and closure. Events are written to `investigation_case.case_journey_event` in the same database transaction as the case change and delivery outbox event. Migration V3 backfills earlier local events from the outbox. The journey page is at `http://localhost:18082/case-journey.html?caseId={caseId}`.

Run the synthetic routing checks against the running services:

```sh
python3 case_routing_scenarios.py
```

This creates a KYC case routed from L1 to L3 and closed by a second actor after its required review, a sanctions case routed to a specialist and returned to an analyst, and a baseline case routed from L1 to L5 and back. It checks invalid routes, incomplete-step closure, a stale revision, action replay, audit history, and the journey response. Results and journey/playbook monitor links are saved to `../../.cache/case-routing-scenarios.json`. Use [Case Swagger](http://localhost:18082/swagger-ui.html) for the case-action and journey endpoints and [Playbook Swagger](http://localhost:18083/swagger-ui.html) for the readiness endpoint.

These actor IDs are unverified development headers. Authentication, role and stage membership, assignment enforcement, evidence sign-off, agent decisions, and policy approval are not yet implemented. The demo task-completion endpoint and the internal Playbook close endpoint are also unauthenticated in this local slice; production access controls must guard them before deployment.

## Current integration flow

1. Alert ingestion stores a canonical alert with a unique `(tenant, source system, source alert ID)` key and writes `AlertAccepted` to its outbox in the same transaction.
2. A local outbox relay sends verified alerts to Case by HTTP. It retries undelivered events; the case link is idempotent by canonical alert ID.
3. Case obtains grouping metadata and evaluates the final alert combination through Policy. A subject-level database lock serializes attachment, and each link advances the stable case revision.
4. Case writes `CaseAlertLinked` and, when applicable, `CaseClassificationChanged` to its outbox. Its local relay sends the latest case snapshot to Playbook.
5. Playbook reconciles the case revision idempotently, retains the product-to-Flowable ID mapping, activates the KYC and combined-risk tasks, and writes `PlaybookRevised` to its outbox.

The local relays are adapters for this development slice. Kafka publishing and consumption are still planned. Unconsumed `CaseClassificationChanged`, `CaseRouted`, and `PlaybookRevised` rows remain in their outboxes for that work. Authentication, agent execution, bank API sections, policy publication/approval, evidence decision gates, and CI/CD remain later work; the existing spike demonstrates dynamic step injection and Python external-worker execution separately.
