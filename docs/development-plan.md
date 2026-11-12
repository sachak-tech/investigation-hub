# Investigation Hub: development plan

Status: Milestone 0 complete; Milestone 1 first slice passing; later milestones remain the proposed implementation sequence, 12 November 2026

The current architecture baseline is in [agentic-investigation-hld.md](agentic-investigation-hld.md). Later design changes should be recorded as explicit decisions with their effects on contracts, data ownership, and in-flight cases.

## Baseline for the first implementation

- Next.js investigator and bank-configuration UI.
- Ten independently deployable backend services: nine Java 25/Spring Boot 4 services and one Python 3.13/FastAPI Agent orchestration service.
- Flowable Open Source CMMN/DMN embedded in the Java Playbook service. Python agents execute Flowable external-worker tasks through the Playbook API.
- PostgreSQL owned by each service, Kafka for domain events, bank-approved object storage for evidence.
- One synthetic bank policy and synthetic customer/transaction data. No regulatory behaviour is inferred from an illustrative policy.

## Milestone 0: contracts and Flowable feasibility

Deliver these contracts before implementing the full service set:

1. Define stable IDs and ownership for alert, subject, case, case revision, policy version, playbook revision, step definition, step run, evidence, and decision.
2. Define the first policy schema: case matching window, alert combination, classification priority, required steps, stage graph, agent authority, and closure gate.
3. Define versioned command/event contracts for `AcceptAlert`, `AttachAlert`, `CompleteStep`, `RequestClosure`, `AlertAccepted`, `CaseAlertLinked`, `CaseClassificationChanged`, `PlaybookRevised`, `StepCompleted`, and `DecisionInvalidated`.
4. Define the step destination and external-section schema: context inputs, connector, response validation, table/card presentation, access, and evidence mapping.
5. Build a small executable Flowable Open Source spike with PostgreSQL and synthetic data. Demonstrate a running CMMN case receiving a new alert, activating a previously modeled step, dynamically injecting a newly approved step, retaining completed tasks, assigning a human task, and executing a Python external-worker task.

Exit criterion: the spike demonstrates the active-case change against a pinned open-source Flowable version. If dynamic injection or model migration cannot preserve the required history safely, revise the Flowable mapping before building the production workflow service.

Result: **passed** on Flowable 8.0.0 with local PostgreSQL. The active case retained its ID and completed plan items after a new alert activated a KYC review task and a separate approved step was injected. A Python external worker completed the agent task. See [Milestone 0 results](milestone-0-results.md) for the evidence and limits.

## Milestone 1: platform foundation

- Create separate deployable projects and database ownership boundaries for the 10 services. Use one repository initially for coordinated contracts and builds.
- Establish local development infrastructure: PostgreSQL databases, Kafka, object storage emulator, and the Playbook service with Flowable.
- Establish local build, migration, contract-check, and integration-test commands. CI/CD pipelines are deferred as requested.
- Expose a versioned OpenAPI document and Swagger UI for each Java HTTP service, with owned endpoints, request/response schemas, and error responses. Keep API documentation enabled in local development and define its production access policy when authentication is integrated.
- Add correlation IDs, structured logs, metrics, traces, and secret references. Authentication and service-to-service authorization are deferred as requested; preserve tenant/actor context and authorization seams in APIs.
- Implement transactional outbox publishing, idempotent consumers, and case revision checks in the relevant service templates.

Exit criterion: a synthetic alert can travel across service boundaries with a traceable event ID, and each service can be deployed and migrated independently.

Progress: the first four services have independent jars, schemas, Flyway migrations, readiness probes, and Swagger UI. A synthetic alert travels through Alert, Policy, Case, and Playbook, with correlation and outbox event IDs. The [verified first slice](milestone-1-slice-results.md) passes from a stopped state. The other six services, Kafka transport, and broader foundation controls remain to complete this milestone.

## Milestone 2: one end-to-end investigation slice

Use this illustrative scenario:

1. A transaction alert creates a case and starts a CMMN investigation.
2. A related KYC source-of-funds alert arrives within the configured joining window; the same case ID remains, its classification changes, and required KYC/combined-analysis steps appear.
3. A Python agent completes one research step, attaches sourced evidence, and returns a structured recommendation.
4. A bank-configured step opens an external API-backed section in Next.js, with case context preselected and data rendered as a table or cards.
5. A reviewer completes the human step and routes the case through bank-configured stages. The first fixture should include L1 through L5 even if the scenario skips some stages.
6. The Decision service evaluates the final authority gate; only the Case service commits closure with the expected case revision.

Implement only the minimum capability required in each service for this slice. The Search service may begin as a simple read projection. Use synthetic data and a mock bank API until integration contracts are proven.

Exit criterion: the scenario is repeatable from alert ingestion through closure, including a full audit of the policy version, alert attachment, classification change, playbook changes, agent work, reviewer action, and evidence sources.

## Milestone 3: failure and control scenarios

- A new alert arrives while closure is being attempted: stale closure fails and new steps appear.
- Duplicate or out-of-order alerts do not create duplicate case links or step runs.
- Failed bank API and agent calls retry safely; unresolved failures leave the step open and visible.
- A bank publishes a new playbook version while cases are active: migration follows an explicit approved rule.
- Users and agents cannot access fields or actions outside their tenant, role, stage, and policy authority.
- A low-risk autonomous disposition is permitted only by a configured rule and independent check; other outcomes route to human review.
- Load-test alert bursts, concurrent case revisions, Flowable task volume, and external connector throttling.

Exit criterion: every failure has a recoverable state, a traceable reason, and no silent loss of alerts, steps, or evidence.

## Milestone 4: bank onboarding and broader coverage

- Add bank policy document extraction into draft structured configuration, with compliance-owner review, simulation, approval, and publication.
- Add more alert families and specialised playbook modules without changing the case/step contracts.
- Add production connector administration, environment promotion, data residency, retention, and bank-specific deployment controls.
- Measure agent quality and reviewer overrides before widening autonomous authority.

## Current handoff

The first four-service slice is implemented, including synthetic routing and closure beyond the original Milestone 1 plan. See [PROJECT_STATE.md](PROJECT_STATE.md) for exact current behavior, verification commands, known limits, and next work. The remaining items in this plan are target capabilities, not claims of completion.
