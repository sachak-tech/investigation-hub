# Milestone 1 first slice results

Verified 12 November 2026 against the user's local PostgreSQL server. Four independent Spring Boot 4.1.2 services built under the Maven reactor, with Springdoc 3.2.0 Swagger UI, Flyway, health/readiness probes, and correlation IDs. The Playbook service embeds Flowable Open Source 8.0.0.

`bash scripts/run-local.sh --smoke` completed from a stopped state and printed `PASS`. The test asserted:

- All four readiness endpoints returned `UP` and all four OpenAPI 3.1 documents loaded.
- Retrying the same source transaction alert returned the same canonical alert ID and accepted event ID.
- The transaction alert created a case at revision 1 and an active Flowable transaction playbook.
- A verified KYC alert for the same subject joined the same case within the synthetic policy window; classification changed to `TRANSACTION_AND_KYC_REVIEW` at case revision 2.
- Playbook revision advanced to 2 while retaining the same Flowable case instance ID. Source-of-funds and combined-risk review tasks became active.
- Alert and Case outbox relays delivered all tested `AlertAccepted` and `CaseAlertLinked` events. Product and Flowable tables were isolated in their assigned schemas.

The test used synthetic data only. The development relay uses HTTP rather than Kafka; the two event types without consumers remain in the outbox. Service-to-service authentication, policy approval, agent and human completion, external bank data, closure gates, and the remaining six services are not implemented in this slice.
