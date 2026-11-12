# Investigation Hub: agent handoff

Read [docs/PROJECT_STATE.md](docs/PROJECT_STATE.md) before changing code. It is the current implementation handoff. Read [docs/agentic-investigation-hld.md](docs/agentic-investigation-hld.md) for the target design and [Backend Projects/platform/README.md](<Backend Projects/platform/README.md>) for local commands.

Rules for work in this repository:

- Inspect code and run the relevant local check before claiming a feature exists. The design documents include future capabilities that are not implemented.
- Treat the three seeded bank policies and all demo data as synthetic. Do not infer regulatory requirements or production approval from them.
- Keep policy-driven case review stages separate from Flowable CMMN playbook tasks. Case stage transitions are currently implemented in Case service and are not shown in the Flowable model diagram.
- Preserve case policy version, case revision, action audit, and durable journey events when changing routing. A new alert may change case type and required playbook steps.
- Keep credentials and local database data out of Git. Use environment variables. Do not commit generated `target/` or `.cache/` content.
- If you add a feature, update `docs/PROJECT_STATE.md` with its exact status, verification command, and remaining limits. Update the relevant README when commands or API behavior change.

The next planned work and known limits are in [docs/PROJECT_STATE.md](docs/PROJECT_STATE.md). Ask the user for the next priority if the task is not explicit; do not assume the full target architecture already exists.
