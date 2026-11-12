# Flowable Milestone 0 spike

This is an executable **synthetic** feasibility spike, not a production case service. It proves that Flowable Open Source CMMN can activate a KYC step after a new alert, inject an approved additional step into an active case, preserve completed work in history, and dispatch an external-worker task to Python. The artificial case classification change is shown as a Flowable variable; production classification remains owned by the separate Case service.

## Local prerequisites

- Java 25, Maven 3.9+, Python 3.9+ (the production Agent service target is Python 3.13).
- An existing PostgreSQL server. Docker is not required.
- A dedicated schema in the `postgres` database. The default JDBC URL targets `jdbc:postgresql://localhost:5432/postgres?currentSchema=investigation_flowable_spike` and user `postgres`; override with `SPIKE_DB_URL` and `SPIKE_DB_USER` if needed.

Create the schema once with a database account permitted to do so:

```sh
psql -h localhost -p 5432 -U postgres -d postgres \
  -c 'CREATE SCHEMA IF NOT EXISTS investigation_flowable_spike AUTHORIZATION postgres'
```

Set `SPIKE_DB_PASSWORD` in your environment without committing it to a file, then run:

```sh
mvn package
java -jar target/flowable-spike-0.1.0.jar
```

In another terminal:

```sh
python3 smoke.py
```

The smoke script starts a unique case and prints `PASS` when all checks succeed. The Flowable runtime and history tables are created only in the configured schema. The sample Python worker uses the standard library and performs no model calls or bank API calls.

## Swagger and OpenAPI

With the spike running, open [Swagger UI](http://localhost:18080/swagger-ui.html) to inspect and call the eight synthetic API operations. The generated [OpenAPI 3.1 JSON](http://localhost:18080/v3/api-docs) can be used for contract review or client generation. Springdoc 3.2.0 is pinned for the Spring Boot 4.1.2 spike. These development endpoints are currently unauthenticated; access controls will be added with the deferred authentication work before production use.

## Visual case monitor

With the spike running, open [http://localhost:18080/monitor.html](http://localhost:18080/monitor.html). Click **Create demo case** to see its transaction and agent steps. Use **Add KYC alert** and **Inject extra step** to watch new tasks appear on the same case. The page refreshes every two seconds and shows completed steps from Flowable history. Run `python3 worker.py` in another terminal to see the Python agent finding arrive. You can also paste a `flowableCaseInstanceId` printed by `smoke.py` to inspect that case.

This is a local debugging monitor for the spike. The production investigator interface will be implemented in Next.js later.

## Scope

The spike deliberately uses direct calls and synthetic identities. Later services will add policy publication, case joining, approved decision gates, evidence storage, tenant authorization, retries, and audit records using the contracts in the repository. Authentication and CI/CD are deferred; API boundaries, tenant/actor fields, and secret references are defined so they can be integrated later.
