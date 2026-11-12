# Milestone 0 results

Completed 12 November 2026 using synthetic investigation data.

## Delivered

- Versioned policy, command, event, step-destination, and external-section schemas with matching examples.
- Product ID, service ownership, case revision, playbook revision, and closure invariants in [contracts.md](contracts.md).
- A Java 25/Spring Boot 4.1.2 spike embedding Flowable Open Source 8.0.0 CMMN, with PostgreSQL runtime and history in the dedicated `investigation_flowable_spike` schema.
- A Python external worker that completes an agent task through the spike API.

## Verified scenario

`mvn -o -DskipTests package` built successfully. `python3 smoke.py` returned `PASS` against the user's local PostgreSQL server at `localhost:5432`:

1. A transaction case started with a human review task and a Python external-worker task.
2. The worker completed its task; the human task was completed.
3. A KYC alert was added to the same Flowable case instance, which activated the source-of-funds task and changed the illustrative classification variable.
4. A separate approved extension model injected an additional human review task into the active case.
5. Flowable history retained both completed plan items while the new tasks were active.

PostgreSQL reported 37 Flowable tables in `investigation_flowable_spike` and no `act_%` tables in `public`. `scripts/validate_contracts.py` passed all five example documents against their JSON Schema draft 2020-12 definitions and checked their cross-references.

## Limits of the spike

The spike hardcodes one synthetic scenario to test engine behavior. It does not implement policy publication, alert matching, product-level case revision, evidence persistence, independent closure checks, authentication, CI/CD, or all ten production services. These are specified in the contracts and development plan for later milestones. The demonstrated path uses dynamic injection; broader model migration and safe handling of all in-flight change combinations require later integration and failure testing.
