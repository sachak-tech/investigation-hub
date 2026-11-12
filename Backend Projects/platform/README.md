# Investigation Hub platform foundation

This directory holds four Java services, one Python agent service and one shared Java library. It is a synthetic investigation slice, not the complete ten-service platform.

| Service | Port | PostgreSQL schema | Swagger UI |
| --- | ---: | --- | --- |
| Policy | 18081 | `investigation_policy` | [Policy API](http://localhost:18081/swagger-ui.html) |
| Case | 18082 | `investigation_case` | [Case API](http://localhost:18082/swagger-ui.html) |
| Playbook | 18083 | `investigation_playbook` for product mappings, `investigation_flowable` for Flowable CMMN | [Playbook API](http://localhost:18083/swagger-ui.html) |
| Alert ingestion | 18084 | `investigation_alert` | [Alert API](http://localhost:18084/swagger-ui.html) |
| Agent (Python; optional local startup) | 18085 | `investigation_agent` | [Agent API](http://localhost:18085/docs) |

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

To include the Bank Document Review Agent, follow [agent-service/README.md](agent-service/README.md) to install Python 3.13 dependencies and the local Ollama model, then start with `IH_START_AGENT_SERVICE=true bash scripts/run-local.sh`. Run `python3 agent_scenario.py` to exercise the actual local model, human return/rework, acceptance, and missing-information handling. Agent-run findings do not complete a task; human acceptance through Case's review endpoint completes it. The [agent policy example](examples/bank-document-agent.v1.json) declares `AGENT_THEN_HUMAN` ownership and a required human review gate.

## Additional demo policies and cases

Three synthetic policies are seeded into Policy on startup, one published policy per tenant:

| Tenant | Policy fixture | Case coverage | Grouping window |
| --- | --- | --- | ---: |
| `demo-bank` | [Baseline AML policy](policy-service/src/main/resources/policies/synthetic-policy.json) | Transaction and transaction plus KYC | 30 days |
| `demo-expanded-bank` | [Expanded AML policy](policy-service/src/main/resources/policies/expanded-aml-policy.json) | Transaction, KYC, sanctions, PEP, and selected combinations | 30 days |
| `demo-sanctions-bank` | [Sanctions policy](policy-service/src/main/resources/policies/sanctions-policy.json) | Sanctions, PEP, and sanctions plus PEP | 14 days |

Readable synthetic source policies are in [Expanded AML policy text](policy-service/src/main/resources/policies/expanded-aml-policy.md) and [Sanctions policy text](policy-service/src/main/resources/policies/sanctions-policy.md). The adjacent JSON documents are the manually prepared executable representation currently seeded into PostgreSQL; policy-text ingestion and approval are later work.

### Dynamic policy proof

With the four services running, execute `python3 dynamic_policy_scenario.py`. It saves an immutable v1 draft, publishes it, creates a case pinned to v1, then validates and publishes v2 with a bank-defined required human review step and a genuine optional follow-up step. A new v2 case receives both tasks through Flowable dynamic plan-item injection. The required step must complete before closure; the optional step can remain open and is terminated on closure. The scenario checks policy simulation, the destination ID, completion, independent approval, and the unchanged v1 case. It writes monitor links to `../../.cache/dynamic-policy-scenario.json`.

The reviewable policy examples are [v1](examples/dynamic-policy.v1.json) and [v2](examples/dynamic-policy.v2.json). The development-only Policy Swagger endpoints are `POST /v1/policies/drafts/simulate`, `POST /v1/policies/drafts`, and `POST /v1/policies/drafts/{policyId}/versions/{version}/publish?tenantId=...`. They are disabled unless `IH_DEMO_POLICY_ADMIN_ENABLED=true`; the local runner enables them for synthetic testing. Publishing retires the previous version for that tenant. Each existing case remains pinned to its original version. Route destinations can be configured by action under `reviewStages[].actionTargets`.

This slice injects **human** bank-added steps from a generic CMMN extension model and stores a product step-to-Flowable plan-item mapping. Agent-owned dynamic steps, dependencies among newly added steps, bank policy text extraction and human approval, authenticated policy administration, and external API data rendering remain future work. Step destinations now resolve through the navigation API below; the Next.js investigator workspace is still pending.

### Step navigation

Click a selected step's name in the Playbook monitor to open its section in a development preview. `GET /v1/playbooks/cases/{caseId}/navigation` returns navigation for all selected steps, and `GET /v1/playbooks/cases/{caseId}/steps/{stepId}/navigation` resolves one step. The response includes the pinned policy version, required/optional flag, section, focus field, resolved case/customer context, data points, evidence requirements, and the relative route intended for the future Next.js workspace. Opening a section does not complete the step or fetch external data.

New policies can include a policy-scoped `destinations` array. The [v2 example](examples/dynamic-policy.v2.json) maps `external_bank_documents` to a built-in `bank-documents` section and maps `customerId` from `case.primarySubjectId`. Common destinations from the original seeded policies use a built-in compatibility catalog. Unknown destination IDs return `available=false`; unselected steps return 404. If Playbook is awaiting case reconciliation, navigation returns 409 rather than using a stale selected-step set.

External sections are explicitly deferred. The contract reserves `kind=EXTERNAL_SECTION` and `externalSectionRef`; the current resolver returns them as unavailable and never calls an external API. A future connector/section renderer can use the same destination/context contract without changing step IDs or the navigation API. Destination changes must be published in a new policy version, preserving existing case navigation. Run `python3 dynamic_policy_scenario.py` to verify configured destinations, context mapping, optional navigation, invalid configuration rejection, and unselected-step rejection along with the policy lifecycle.

With all four services running, create six persistent demo cases and eleven alerts:

```sh
cd "Investigation HUB/Backend Projects/platform" # from the parent of the cloned repository
python3 demo_scenarios.py
```

The script is idempotent for its fixed synthetic source alert IDs. It verifies that alerts are linked to the intended cases and that Flowable activates exactly the required policy steps. It prints case IDs and monitor links and saves the result to `../../.cache/demo-scenarios.json`. Open a case in the [Playbook monitor](http://localhost:18083/playbook-monitor.html) to inspect the live step state. These fixtures demonstrate classification and step activation; case routing and closure can be exercised separately below. Agent execution, closed-case linkage, and policy-text ingestion and approval are still later implementation work.

To see task dependencies, run `python3 linked_scenarios.py` while the services are running. It creates four fresh cases and ten alerts: waiting and activated examples for each of two case types. In transaction-plus-KYC, combined risk review waits for both human reviews. In transaction-plus-KYC-plus-PEP, initial human and agent steps run in parallel, combined risk waits for transaction and KYC reviews, and cross-signal risk waits for combined risk and PEP reviews. The script completes synthetic human tasks where needed and checks each state transition, then writes `../../.cache/linked-scenarios.json` with monitor links. The local runner enables `/v1/playbooks/demo/cases/{caseId}/tasks/{taskId}/complete` only for synthetic `demo-*` tenants; this development action is disabled by default outside the local runner. It does not replace the planned authenticated task-completion and evidence workflow. Existing cases retain the CMMN definition version with which they started, so use the newly created case IDs to inspect the linked behavior.

## Case review lifecycle

### Evidence-backed task completion

Run `python3 task_completion_scenario.py` against the running platform. It registers supplied synthetic text, completes the bank-document review with exact line citations, checks rejection of missing/unknown/cross-case/mismatched citations and stale revisions, tests concurrent identical retries and changed-command rejection, then verifies closure and activation of linked tasks. The output includes a step preview showing saved findings and citations; the manifest is saved to `../../.cache/task-completion-scenario.json`.

- `POST /v1/playbooks/cases/{caseId}/documents`: register immutable supplied text with `documentId`, `title`, `sourceReference`, and `content`. Supply `X-Tenant-Id` and `X-Actor-Id`. Text is normalized to LF before calculating its SHA-256. This stores supplied text locally; it does not fetch documents or establish their authenticity.
- `POST /v1/cases/{caseId}/steps/{stepId}/complete`: submit the [task completion request](../../examples/task-completion.v1.json), using the active task ID and current case/playbook revisions. Supply `X-Tenant-Id`, `X-Actor-Id`, and `X-Correlation-Id`. Case holds its row lock during the downstream completion call. Use this public Case endpoint, not the internal Playbook transaction endpoint.
- `GET /v1/playbooks/cases/{caseId}/completions`: read immutable findings and citation receipts with `X-Tenant-Id`. `GET .../documents` returns supplied source metadata. The step preview shows receipts for its selected step.

The new operation checks the pinned policy, selected active step, task-to-step mapping, human ownership, current stage's `COMPLETE_STEP` permission, existing task assignee, required evidence types, and each citation's case ownership, line range and exact excerpt. Findings, verified source references/hashes, completion receipt, Flowable task completion, playbook revision increment and `StepCompleted` outbox event commit together in the Playbook database. The command UUID is an idempotency key: identical requests return the original receipt even after later case changes; changed reuse returns 409. Validation failures leave no completion record and keep the task open.

This human-review completion path is also used when a human accepts the first AI agent's findings. It validates traceability and configured requirements, not whether a conclusion or document is truthful. Header identities and internal routes still require authentication, tenant/role membership enforcement, and protected service ingress before production. Cross-service crash/timeout fencing is not a distributed transaction and remains failure-testing work. `StepCompleted` is retained in Playbook's outbox; it is not yet projected into Case's journey timeline. Existing `demo/.../complete` endpoints remain synthetic-only regression helpers behind the development flag and bypass evidence requirements.

Playbook steps are investigation work tracked by Flowable. Case actions are review decisions tracked by Case. A case starts at the first `reviewStages` entry of its pinned policy version. The current stage and assignee appear in `GET /v1/cases/{caseId}`. `GET /v1/cases/{caseId}/available-actions` returns the actions and target stages permitted by that policy at the current stage, with an `enabled` flag and reason for a blocked closure. The caller supplies the development-only `X-Actor-Id` header.

`POST /v1/cases/{caseId}/actions` accepts `ESCALATE`, `RETURN_FOR_REWORK`, or `APPROVE_CLOSE`, with an action UUID, expected case revision, reason, and target stage or assignee for a handoff. Case records every accepted decision in `GET /v1/cases/{caseId}/actions`. Routing advances the case revision but does not complete a playbook step; playbook work may continue at the new review stage. Completing the playbook also does not close the case automatically. `APPROVE_CLOSE` is enabled only at the policy's human approval stage, after all required playbook steps have completed, and by an actor different from the preceding reviewer when the policy requires an independent check. Closing Case emits an event that closes the associated Flowable case. A new alert can change case type and required steps; closure checks the latest required step set. The current review stage stays in place until an explicit case action routes it.

`GET /v1/cases/{caseId}/journey` returns one revisioned view of the current stage, assignee, pinned policy stage map, and ordered case events: creation, alert links, classification changes, escalations, returns, and closure. Events are written to `investigation_case.case_journey_event` in the same database transaction as the case change and delivery outbox event. Migration V3 backfills earlier local events from the outbox. The journey page is at `http://localhost:18082/case-journey.html?caseId={caseId}`.

Run the synthetic routing checks against the running services:

```sh
python3 case_routing_scenarios.py
```

This creates a KYC case routed from L1 to L3 and closed by a second actor after its required review, a sanctions case routed to a specialist and returned to an analyst, and a baseline case routed from L1 to L5 and back. It checks invalid routes, incomplete-step closure, a stale revision, action replay, audit history, and the journey response. Results and journey/playbook monitor links are saved to `../../.cache/case-routing-scenarios.json`. Use [Case Swagger](http://localhost:18082/swagger-ui.html) for the case-action and journey endpoints and [Playbook Swagger](http://localhost:18083/swagger-ui.html) for the readiness endpoint.

These actor IDs are unverified development headers. Authentication, role and stage membership, queue/assignment management, semantic evidence sign-off, autonomous case decisions, and human policy approval are not yet implemented. The demo task-completion endpoint and internal routes are unauthenticated in this local slice; production access controls must guard them before deployment.

## Current integration flow

1. Alert ingestion stores a canonical alert with a unique `(tenant, source system, source alert ID)` key and writes `AlertAccepted` to its outbox in the same transaction.
2. A local outbox relay sends verified alerts to Case by HTTP. It retries undelivered events; the case link is idempotent by canonical alert ID.
3. Case obtains grouping metadata and evaluates the final alert combination through Policy. A subject-level database lock serializes attachment, and each link advances the stable case revision.
4. Case writes `CaseAlertLinked` and, when applicable, `CaseClassificationChanged` to its outbox. Its local relay sends the latest case snapshot to Playbook.
5. Playbook reconciles the case revision idempotently, retains the product-to-Flowable ID mapping, activates the KYC and combined-risk tasks, and writes `PlaybookRevised` to its outbox.

The local relays are adapters for this development slice. Kafka publishing and consumption are still planned. Unconsumed domain events remain in their outboxes for that work. Authentication, additional agent types, bank API sections, human policy approval, broader evidence decision gates, and CI/CD remain later work. The spike's automatic Python external-worker task acquisition remains separate from the new explicitly requested Bank Document Review Agent runs.
