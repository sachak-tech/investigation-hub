# Milestone 0 contracts

Status: v1 contracts for the synthetic Flowable spike, 12 November 2026. Production policy approval and authorization are later milestones.

Schemas live in [`contracts/`](../contracts/) and examples in [`examples/`](../examples/). The example policy is deliberately synthetic; its 30-day join window, case labels, and review stages are not regulatory defaults.

## Identity and ownership

| Identifier | Owner | Invariant |
| --- | --- | --- |
| `alertId` | Alert ingestion | One source event maps to one canonical alert; duplicate deliveries reuse the ID. |
| `subjectId` | Subject resolution | Cross-source identity link is explicit and carries provenance/confidence. |
| `caseId`, `caseRevision` | Case | Case ID never changes when classification changes; revision increments on alert links, classification, or status change. |
| `policyId`, `policyVersion` | Policy | Published versions are immutable; every case decision records the evaluated version. |
| `playbookRevision`, `stepRunId` | Playbook | New alerts produce a new revision; completed step runs remain historical. |
| `flowableCaseInstanceId`, `planItemInstanceId` | Playbook | Internal execution IDs map to product IDs; they do not become external case IDs. |
| `evidenceId` | Evidence | Evidence has source, retrieval time, content hash/reference, and retention metadata. |
| `decisionId` | Decision | Decision records its case revision, policy version, actor, and evidence references. |

## Service commands and events

The [versioned command envelope](../contracts/command.schema.json) defines `AcceptAlert`, `AttachAlert`, `CompleteStep`, and `RequestClosure`. Planned service APIs expose these as `POST /v1/alerts`, `POST /v1/cases/{caseId}/alerts`, `POST /v1/cases/{caseId}/steps/{stepRunId}/complete`, and `POST /v1/cases/{caseId}/closure-requests`. Commands that mutate an existing case carry `expectedCaseRevision`; stale revisions fail with a conflict. The same `idempotencyKey` and command ID return the original result on retry.

Alert ingestion emits `AlertAccepted`. Subject resolution supplies a verified or review-required subject link. Case evaluates approved policy and emits `CaseAlertLinked`, followed by `CaseClassificationChanged` when applicable. Playbook reconciles its Flowable case instance and emits `PlaybookRevised`. Agent and human completions emit `StepCompleted`. If a new alert makes a recommendation stale, Decision emits `DecisionInvalidated`.

Every event uses the [domain event envelope](../contracts/domain-event.schema.json): tenant, aggregate revision, correlation, actor, and policy version where applicable. Producers write the domain change and outbox entry in one transaction. Consumers deduplicate by `eventId` and process case revisions in order or detect a gap and recover. Event payloads contain IDs and compact facts; evidence files remain in the Evidence service.

## Case attachment and closure

Joining requires a verified same subject, open compatible case, configured window, and matching bank policy. Ambiguous links await review. When a new alert is attached, Case increments `caseRevision`. A close command must include `expectedCaseRevision` and a completion/approval result for that revision; Case rejects stale commands. A closed case receives a linked new case unless an approved reopen rule applies.

## Playbook revision

A step's `activation.allAlertTypes` must all be present; if `anyAlertTypes` is nonempty, at least one must be present; if `caseTypes` is nonempty, the current classification must match one. Empty `anyAlertTypes` and `caseTypes` impose no restriction. These rules are evaluated when the case changes, then the Playbook service reconciles required steps against the prior revision. A revision lists added, retained, rechecked, and superseded steps. Completed step runs and evidence are never deleted. Flowable plan-item history is kept alongside the product-level revision record.

The Playbook service records the mapping from approved step definition and [navigation destination](../contracts/step-destination.schema.json) to Flowable plan item. Clicking a step opens its `sectionId` in the case investigation workspace with mapped case context and optional field focus. A destination can point to a built-in section or to a bank-configured [external section](../contracts/external-section.schema.json). The UI resolves the destination ID; banks do not supply arbitrary browser URLs.

## External section and future authentication seam

The UI receives a destination definition and case context from Playbook. Connector resolves the configured endpoint reference server-side, validates inputs and response, and returns a table/card view model. It never exposes credentials to Next.js. For this milestone, synthetic `tenantId`, `actorId`, and roles are passed explicitly. Later authentication will resolve those fields from a trusted identity context and authorize each API, connector call, and Flowable task action; endpoint shapes and ownership will not change.

`agentAuthority.permittedActions` is an upper bound, not a grant by itself. A close request also has to pass the current case type, disposition, risk, required-step, independent-check, reviewer-stage, and case-revision gates. The example policy permits an agent to complete a step and recommend closure; its autonomous closure list is empty, so no agent can close a case in that example.
