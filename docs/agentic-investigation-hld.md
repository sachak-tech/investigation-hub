# Agentic Investigation Hub: high-level design decisions

Status: working design, 11 November 2026

This document records the product contracts that later backend and UI designs must preserve. Bank-approved, versioned policy controls case assembly, playbooks, review stages, routing, and agent authority.

## Core model

- An **alert** is a source signal, such as transaction monitoring, KYC/CDD, sanctions, fraud, adverse media, or a referral.
- A **case** is the stable investigation record containing related alerts, subjects, evidence, work, and decisions.
- A **case classification** is the current policy-derived investigation type. Its history is retained.
- A **playbook** is composed from core, alert-specific, combination-specific, and disposition modules.
- A **step run** is an instance of a playbook step for a particular case and playbook revision.
- A **review stage** is a bank-configured node with roles, allowed actions, entry and exit conditions, deadlines, and routes. The product does not hardcode L1/L2/L3 or any stage count.

Bank policy is extracted into structured configuration, reviewed, simulated, approved, versioned, and published. Models may assist with extraction and analysis but are not the authority for rules or actions.

## Proposed microservice architecture: 10 backend services

The product is designed as independently deployable services with explicit data ownership. No service reads or writes another service's database. Bank integrations and Next.js clients use versioned APIs; domain events carry changes that other services need to react to. The selected technology proposal is Java 25/Spring Boot 4 for nine domain services, Python 3.13/FastAPI for Agent orchestration, PostgreSQL per service, Kafka for domain events, and Flowable Open Source CMMN/DMN embedded in the Playbook service. The Policy service owns approved bank definitions; the Playbook service deploys and executes their controlled Flowable representation. Temporal is not part of the selected design.

| # | Service | Language and framework | Owns |
| --- | --- | --- | --- |
| 1 | Alert ingestion | Java 25, Spring Boot 4, Spring Kafka | Source adapters, normalization, validation, deduplication, raw alert references. |
| 2 | Subject resolution | Java 25, Spring Boot 4 | Customer/account/beneficial-owner identity links and relationship confidence. |
| 3 | Policy | Java 25, Spring Boot 4, typed declarative rule evaluator | Approved policy versions, combination rules, stage definitions, playbook templates, action authority. |
| 4 | Case | Java 25, Spring Boot 4 | Case identity, alert membership, classification history, case revision, and terminal status. This is the only service that commits case closure. |
| 5 | Playbook and workflow | Java 25, Spring Boot 4, Flowable Open Source CMMN/DMN | Flowable case instances, playbook revisions, step runs, dependencies, routing, work queues, and deadlines. |
| 6 | Agent orchestration | Python 3.13, FastAPI, Pydantic, Flowable external-worker client | Scoped agent tasks, model/tool routing, output validation, and agent execution records. |
| 7 | External data connector | Java 25, Spring Boot 4, WebClient | Bank API configurations, credential references, controlled calls, response validation, and presentation schemas. |
| 8 | Evidence | Java 25, Spring Boot 4 | Documents, source snapshots, provenance, citations, and retention metadata. |
| 9 | Decision | Java 25, Spring Boot 4 | Recommendation assessment, approval records, authority checks, and requests to change case disposition. |
| 10 | Search projection | Java 25, Spring Boot 4 | Read-optimized case search and investigator dashboard views derived from events; it is not a system of record. |

The Next.js application includes its backend-for-frontend layer, which provides a stable client API, authentication context, and assembled investigator views. It is part of the frontend deployment and is not counted among the 10 backend microservices. Kafka, PostgreSQL, object storage, and the audit pipeline are shared infrastructure and are also not counted as business microservices. Flowable is embedded in the Playbook service and does not add a product microservice. A tamper-evident audit pipeline collects domain events and security-relevant actions without replacing each service's transactional records.

### New-alert event flow

`AlertAccepted` -> `SubjectResolved` -> Case service evaluates approved policy and atomically links the alert or creates a new case -> `CaseAlertLinked` and possibly `CaseClassificationChanged` -> Workflow service creates a new playbook revision -> `PlaybookRevised` -> agent tasks and human work queues update. If an earlier recommendation is no longer valid, the Decision service marks it stale.

Use transactional outbox publishing, idempotent consumers, stable event IDs, and per-case revision numbers. Consumers may lag, so the UI exposes revision and processing status. Case membership and closure are serialized by the Case service: a close request carries the expected case revision, and closure fails if a new alert has changed that revision. The workflow and decision gates must confirm that all required work for that revision is complete before closure is committed.

Each service owns its PostgreSQL schema or database. Cross-service reads use APIs or read projections, not SQL joins across service databases. Kafka carries integration and domain events; synchronous calls are reserved for decisions that need an immediate answer. The Playbook service owns the Flowable database schema and a mapping from product case/step IDs to Flowable case/plan-item instance IDs. Flowable executes CMMN task transitions, human work, external-worker jobs, and timers. The Playbook service reconciles new alerts and approved policy changes into its Flowable case instances and publishes results through an outbox. Python agent workers acquire jobs through a controlled Playbook API and report results with stable IDs and idempotency checks.

### Flowable execution boundary

Flowable Open Source is a Java engine embedded in the Playbook service, not a separate hosted account. Its runtime and history tables live in that service's PostgreSQL database. The Case service remains authoritative for case ID, alert membership, classification, and closure. The Policy service remains authoritative for approved policy and playbook definitions. Flowable is authoritative for runtime plan-item/task state. Evidence and regulatory audit records remain in product stores; Flowable history supports workflow traceability but does not replace them.

The Playbook service deploys versioned CMMN/DMN models from approved configuration. A new alert can activate already-modeled steps by changing case variables/events. A bank's newly approved step for an active case requires controlled model deployment and either dynamic plan-item injection or case migration, with completed work and model mappings retained. A feasibility spike must verify these operations in the pinned open-source Flowable release before broad implementation.

## Case and workflow revision

When a new alert arrives, the Case service checks subject relationship, joining window, case status, jurisdiction, policy compatibility, and other bank-defined criteria. If eligible, it attaches the alert to the same stable case ID and recalculates classification, risk, playbook, routing, and decision authority.

The Playbook service creates a new **playbook revision**:

1. Keep completed step runs and their evidence.
2. Add newly required steps, including steps caused by combinations of alerts.
3. Mark no-longer-applicable pending steps as superseded, with a policy reason. Never erase them.
4. Recheck completed steps whose conclusions may be invalidated by the new alert. Reopen the decision gate if necessary.
5. Recalculate queues, review stages, deadlines, and autonomous-action eligibility.
6. Record the alert, old and new classification, policy version, and workflow diff in the audit trail.

A late alert on a closed case normally creates a linked case; reopening requires explicit policy. In-flight case migration between policy versions must also be explicit.

## Playbook step navigation is a product contract

Every investigator-facing step has a **destination**. Selecting a step opens the exact workspace where its investigation can be performed, with the case, subject, alert, and step context already selected. The investigator must not have to search for the relevant screen or reconstruct the context.

A destination may be a built-in investigation view, a bank-configured external data section, a review form, or an approved external application deep link. The step definition references a stable destination ID and passes structured context; it does not embed a fragile URL or hardcoded screen location.

The destination opens with:

- relevant case and alert identifiers, subject identifiers, time range, and filters;
- the step's investigation question and completion criteria;
- the data needed for that step, with source and freshness labels;
- actions permitted to the current role and stage;
- a way to capture findings, attach evidence, and complete or escalate the step.

If the destination is unavailable, the step remains open and shows a recoverable error. Navigation alone never marks a step complete.

## Bank-configured external sections

A bank may add a playbook step and map it to an **external section** that it configures. This is a first-class extension point, not a future custom UI project. The bank supplies a connector definition and a presentation definition:

| Configuration | Purpose |
| --- | --- |
| Connector | Approved API endpoint or integration, authentication reference, allowed operations, timeout, and retry policy. |
| Input mapping | Map case, subject, alert, and date context into typed API request fields. |
| Response schema | Validate and normalize the returned fields, types, and pagination. |
| Presentation | Render approved fields as a table, cards, or a detail panel; configure labels, sorting, filters, and drill-down. |
| Evidence mapping | Specify which returned fields may be saved as cited evidence and their source metadata. |
| Access policy | Define who and which agents may view or invoke the section, including field masking. |

The platform calls the API through a controlled backend connector. The browser does not receive bank API credentials. Configuration uses approved schemas and renderers rather than arbitrary bank-supplied frontend code. Connector access has allowlists, secret storage, request logging, rate limits, and tenant isolation.

External data can be live for exploration. If an agent or investigator relies on it for a decision, the relevant response is captured as an evidence snapshot with source, retrieval time, schema version, and provenance. A changed API response must not silently rewrite past evidence.

### Illustrative step configuration

```yaml
step_id: verify_source_of_funds
trigger: case_has_alert("KYC_SOURCE_OF_FUNDS_DISCREPANCY")
owner: agent_then_investigator
destination:
  section_id: bank_source_of_funds
  context:
    customer_id: case.primary_customer_id
    alert_id: triggering_alert.id
    from_date: case.review_period.start
    to_date: case.review_period.end
completion:
  required_findings: [source_of_funds_assessment, discrepancy_resolution]
  evidence_required: true
```

The `bank_source_of_funds` section separately defines its API connector, request and response schema, table or card layout, access policy, and evidence fields. Policy publication validates that every referenced destination and connector exists and that the step's required data and actions are supported.

## Agent and decision boundaries

Agents perform bounded research, enrichment, analysis, synthesis, and independent challenge. A policy decision gate checks authority immediately before closure, escalation, reporting, or any external action. Banks can configure bounded autonomous closure for eligible low-risk scenarios and route other outcomes to their own review stages (for example, L1 through L5). Agent conclusions include source references and unresolved questions.

## Minimum enterprise records

`PolicyVersion`, `Alert`, `Case`, `CaseAlertLink`, `CaseClassificationHistory`, `PlaybookTemplate`, `PlaybookRevision`, `StepDefinition`, `StepRun`, `DestinationDefinition`, `ExternalSectionDefinition`, `ConnectorDefinition`, `EvidenceArtifact`, `Decision`, `Action`, and append-only `AuditEvent`.

The exact storage design remains open, but these concepts and their version/provenance relationships are required.
