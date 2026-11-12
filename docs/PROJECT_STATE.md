# Investigation Hub: current implementation handoff

Updated 12 November 2026. This is a code-state handoff, not a claim that the enterprise platform is complete. Verify against code and tests after each change.

## Product intent

Build a bank-configurable, agentic financial-crime investigation hub. Alerts may create or join a case within a policy-defined window; combinations can change case type and required investigation steps. Review layers and actions are bank-policy driven, not hardcoded to L1/L2/L3. Investigators should review agent work, navigate from a step to the relevant data section, and eventually use bank-configured external API sections. The target UI is Next.js.

## Implemented now

The executable platform is under [`Backend Projects/platform`](<../Backend Projects/platform>). It has four Java 25 / Spring Boot 4.1.2 services: Alert (18084), Policy (18081), Case (18082), and Playbook (18083). Each owns a PostgreSQL schema, Flyway migrations, and Swagger UI. `service-common` is a library, not a service. Local outbox relays use HTTP; Kafka is not wired. See the [platform README](<../Backend Projects/platform/README.md>) for endpoints and commands.

- Alert ingestion is idempotent by tenant, source system, and source alert ID. Verified-subject alerts are delivered to Case.
- Policy deterministically chooses a case type and step IDs from seeded **JSON fixtures** for three synthetic tenants. Readable policy text exists for two fixtures, but text extraction, approval, publication, and tenant administration are not implemented.
- Case groups matching alerts, changes classification/required steps on a new alert, tracks revision, and exposes policy-permitted `ESCALATE`, `RETURN_FOR_REWORK`, and `APPROVE_CLOSE` actions. A closure gate checks the current required playbook steps and the pinned policy's approval stage. The development identity is a caller-supplied `X-Actor-Id` header, not real authentication.
- Case review stages and transitions are implemented in Case service from policy `reviewStages`; **there is no case-level BPMN/CMMN file**. The accepted route and alert/classification history are in the durable `case_journey_event` table and `GET /v1/cases/{caseId}/journey`.
- Playbook uses Flowable CMMN from [`investigation.cmmn`](<../Backend Projects/platform/playbook-service/src/main/resources/cases/investigation.cmmn>). Its shared model contains eight predefined step definitions. Policy-required steps activate at runtime, including linked and parallel steps. Closing Case eventually closes the Flowable instance via the local relay.
- Read-only development views: [case journey](http://localhost:18082/case-journey.html) and [playbook monitor](http://localhost:18083/playbook-monitor.html). The playbook monitor distinguishes required steps from unrelated model items; Flowable's `terminated` state on unused items does not mean those tasks were completed.
- A separate [Flowable spike](<../Backend Projects/flowable-spike/README.md>) demonstrated dynamic step injection and a Python external worker. Those capabilities are **not integrated into the four-service platform**.

## Verification

On macOS/Linux or Windows WSL, install Java 25, Maven, PostgreSQL, `psql`, `curl`, `rg`, `lsof`, and Python 3.9+. Provide a local PostgreSQL database/user with permission to create schemas. From `Backend Projects/platform`, set `IH_DB_PASSWORD` and run `bash scripts/run-local.sh --smoke`. This builds the modules, creates schemas, applies migrations, starts the four services, verifies Swagger and a transaction-plus-KYC case, and stops the services. Use `bash scripts/run-local.sh` to leave them running; run `python3 case_routing_scenarios.py` for routing/closure/journey and `python3 linked_scenarios.py` for dependent tasks. Demo results are written under ignored `.cache/`.

The most recent successful local checks before this handoff were the platform smoke test, three case-routing scenarios, and browser inspection of the case journey and playbook monitor. Re-run on the receiving machine. The runner requires PostgreSQL; Git contains migrations and synthetic fixtures, **not the local database rows or previously created case IDs**. Fresh demo cases will get new IDs.

## Known limits and next work

1. Build the actual Next.js investigator workspace. Step clicks should navigate to the correct data section using policy destination IDs. Bank-configured external API sections, response validation, and card/table rendering exist as contracts only.
2. Add authenticated human/agent identity, tenant and stage permissions, assignment enforcement, evidence sign-off, service-to-service authorization, and protected internal endpoints. Current `X-Actor-Id` is unverified and the local demo completion endpoint is intentionally synthetic.
3. Implement agent orchestration and execution in the platform, with sourced evidence, structured recommendations, evaluation, and controlled autonomous authority. Do not interpret the spike worker as production agent orchestration.
4. Implement policy document intake as draft structured config, simulation, human approval, version publication, and migration rules for in-flight cases. Current fixtures are manually prepared JSON seeded on startup.
5. Model per-action target stages explicitly. The current policy has stage-level `permittedActions` and `nextStages`; when a stage permits two route actions, the backend currently combines each action with every listed target. Clarify `ESCALATE` versus `RETURN_FOR_REWORK` destinations in the next policy schema.
6. Decide and implement true optional playbook steps separately from the current required-step set. Unselected CMMN model items are **not** optional tasks. Add a policy field, activation semantics, and closure behavior before labeling a step optional.
7. Add Kafka, evidence storage, observability, recovery/load testing, CI/CD, and the remaining service boundaries from the [development plan](development-plan.md). Authentication and CI/CD were explicitly deferred for the first slice.

## Source map

| Question | Source |
| --- | --- |
| Target architecture and service split | [HLD](agentic-investigation-hld.md), [plan](development-plan.md) |
| Executable platform and setup | [Platform README](<../Backend Projects/platform/README.md>), [runner](<../Backend Projects/platform/scripts/run-local.sh>) |
| Policy actions and steps | [Seed fixtures](<../Backend Projects/platform/policy-service/src/main/resources/policies>) |
| Case routing and journey | [CaseActionService](<../Backend Projects/platform/case-service/src/main/java/com/investigationhub/cases/CaseActionService.java>), [CaseJourneyService](<../Backend Projects/platform/case-service/src/main/java/com/investigationhub/cases/CaseJourneyService.java>) |
| Flowable playbook | [PlaybookService](<../Backend Projects/platform/playbook-service/src/main/java/com/investigationhub/playbook/PlaybookService.java>), [CMMN](<../Backend Projects/platform/playbook-service/src/main/resources/cases/investigation.cmmn>) |
| Contracts for future UI/connectors | [Contracts](../contracts), [contract notes](contracts.md) |
