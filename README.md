# Investigation Hub

Policy-driven, agentic financial-crime case management platform in development. Start with the [current implementation handoff](docs/PROJECT_STATE.md) to distinguish working code from the target [architecture](docs/agentic-investigation-hld.md). The [repository setup guide](docs/REPOSITORY_SETUP.md) explains how to check in, clone, and run the workspace. [AGENTS.md](AGENTS.md) and [CLAUDE.md](CLAUDE.md) orient coding assistants on another machine.

The [Flowable spike](<Backend Projects/flowable-spike/README.md>) is executable against a local PostgreSQL schema. Docker is not required. It tests active-case playbook changes and a Python external worker using synthetic data.

With the spike running, [Swagger UI](http://localhost:18080/swagger-ui.html) documents its HTTP API and the [case monitor](http://localhost:18080/monitor.html) shows live playbook state.

The [Milestone 1 platform slice](<Backend Projects/platform/README.md>) runs Alert ingestion, Policy, Case, and Playbook as four separate services. It includes alert-to-case classification, policy-selected Flowable steps, case review routing and closure, and a durable case journey. Each service has Swagger UI. The verified scenario and current limits are in the [handoff](docs/PROJECT_STATE.md).

To validate the JSON contracts:

```sh
python3 -m pip install -r requirements-dev.txt
python3 scripts/validate_contracts.py
```

Authentication and CI/CD are deferred for now. The contracts retain tenant, actor, idempotency, and revision fields for their later integration.

The first [Bank Document Review Agent](<Backend Projects/platform/agent-service/README.md>) now runs as a Python/FastAPI service with local Ollama. It analyses supplied text and submits cited findings for a mandatory human review. External data retrieval remains deferred. The agent README covers model/dependency setup and the repeatable live-model scenario.
