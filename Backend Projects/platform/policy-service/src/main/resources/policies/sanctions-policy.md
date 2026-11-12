# Demo Sanctions Bank — screening investigation policy

Policy ID: `synthetic-sanctions-policy`, version 1. Effective 12 November 2026. This is synthetic policy text for development, not a bank-issued policy.

## Case grouping

Join an alert to an open case for the same verified subject only within 14 days of case creation. Otherwise create a new case. Closed cases remain closed; create a linked case for a later alert.

## Classification

- A possible sanctions match alone creates `SANCTIONS_REVIEW`.
- A PEP or adverse-media alert alone creates `PEP_REVIEW`.
- Both alert types on one subject become `SANCTIONS_AND_PEP_REVIEW`, regardless of arrival order.

## Investigation steps

- Sanctions match: human reviews match candidates and customer identifiers and records a match disposition. An agent performs match enrichment.
- PEP or adverse media: human reviews PEP and media hits and records an assessment.
- Both alert types: add a cross-signal assessment covering match candidates, PEP hits, and media hits.
- When the second alert joins the case, retain the existing tasks and add the newly required tasks.

## Review and closure

The screening analyst may complete steps or escalate to a sanctions specialist or compliance officer. The sanctions specialist may complete steps, escalate, or return work to the analyst. The compliance officer may approve closure or return work. Agents may enrich and recommend closure, but no case is closed autonomously. An independent check and compliance-officer approval are required for closure.
