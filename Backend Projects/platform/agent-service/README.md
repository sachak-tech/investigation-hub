# Bank Document Review Agent

The first Python agent service uses Python 3.13, FastAPI, PostgreSQL (`investigation_agent` schema), and local Ollama. Its bounded workflow reads supplied case documents and the pinned policy, asks the local model for findings/citations/gaps, validates citations, derives readiness from gaps and evidence coverage, and submits the result for human review. It has no tool to complete a task or close a case.

## Local setup

From the repository root:

```sh
python3.13 -m venv .cache/agent-venv
.cache/agent-venv/bin/pip install -r "Backend Projects/platform/agent-service/requirements.lock"
```

Install Ollama using its official installer or Homebrew. Start `ollama serve` if it is not already running, then download `ollama pull qwen3:4b`. On a Homebrew installation where the CLI is not linked, use `/opt/homebrew/opt/ollama/bin/ollama`. Model weights remain in Ollama's local storage, not Git. The model is a local proof configuration, not a validated bank production model.

From `Backend Projects/platform`, set `IH_DB_PASSWORD` and run:

```sh
IH_START_AGENT_SERVICE=true bash scripts/run-local.sh
```

This starts four Java services and the agent on port 18085. [Agent Swagger](http://localhost:18085/docs) describes the run API. To run the agent separately while the Java services are already up, use `bash scripts/run-agent-local.sh`. The service applies its owned SQL migration on startup with a database migration lock. Python queue tables are separate from the Playbook review/Flowable tables.

Configuration: `IH_AGENT_MODEL` defaults to `qwen3:4b`, `IH_OLLAMA_URL` to `http://127.0.0.1:11434`, `IH_AGENT_PORT` to 18085. `IH_DB_HOST`, `IH_DB_PORT`, `IH_DB_NAME`, `IH_DB_USER`, `IH_DB_PASSWORD`, `IH_CASE_URL`, and `IH_PLAYBOOK_URL` follow the platform settings. The runtime reads no cloud API key. Readiness reports API/database availability separately from `modelAvailable`; a missing local model rejects new runs with 503.

## Flow and APIs

Use the [agent policy example](../examples/bank-document-agent.v1.json). Its bank-document step has `owner=AGENT_THEN_HUMAN` and `agent={type:BANK_DOCUMENT_REVIEW, requireHumanReview:true, instructions:...}`. The CMMN task is the human review task and remains active while the model runs and findings await review.

`POST /v1/agent-runs` accepts `runId`, `caseId`, `stepId`, `expectedCaseRevision`, and `expectedPlaybookRevision`, with `X-Tenant-Id` and `X-Actor-Id` identifying the development caller. The run UUID is idempotent for the same caller and payload. `GET /v1/agent-runs/{runId}` requires the tenant header. Runs move through `QUEUED`, `RUNNING`, `AWAITING_REVIEW`, then `RETURNED` or `ACCEPTED`; failed execution is `FAILED` with an error code. Store and display the error rather than claiming an analysis succeeded.

The worker uses fixed internal tools: read the current selected step and registered supplied text, validate its result, and submit through Case. It does not follow links in documents or execute model-generated code/tool requests. Input is limited to ten documents, 24000 source characters, and a conservative 12000-byte model-input budget including instructions/schema/repair messages. Oversized context fails rather than being silently truncated. Ollama is called with structured output, temperature zero, no thinking output, and at most two model calls (one repair). The model supplies findings, exact citations and gaps; the service computes `READY_FOR_REVIEW` only when gaps are empty and all required evidence categories have citations. This readiness is not human approval.

Python verifies supplied document hashes, document IDs, required evidence labels, exact excerpts and 1-based line ranges. Case serializes submission against its row lock and Playbook independently validates again, then persists immutable findings in `agent_submission`. No Flowable task is completed on submission. Model name, installed model digest, prompt version and a context hash are retained in the run. Hidden reasoning is neither requested nor stored.

Human review goes through `POST /v1/cases/{caseId}/steps/{stepId}/agent-review` with a command UUID, run UUID, `ACCEPT` or `RETURN_FOR_REWORK`, reason, and expected case/playbook revisions. Acceptance rechecks the original context and uses the existing evidence-backed completion transaction; the receipt links back to `agentRunId`. Rework leaves the task open and saves the review reason for the next run. Attempts to directly complete an agent-configured step, accept findings with gaps, accept stale findings, or review as the agent actor are rejected. Return/review history does not train model weights; it is case-scoped guidance included in the next prompt.

The step preview has development controls to start the agent and accept or return findings. Its same-origin proxy forwards reviews through Case so the case lock is retained. Headers are still unverified development identity; production authentication, real role/tenant authority and protected internal service ingress remain pending.

## Verification and limits

```sh
python3 agent_scenario.py
PYTHONPATH=agent-service ../../.cache/agent-venv/bin/python -m unittest discover -s agent-service/tests -v
```

The live scenario uses the installed Ollama model on supplied synthetic documents, verifies return/rework, acceptance, blocked direct completion and self-review, stale review rejection, and an insufficient-information case that stays open. It writes `.cache/agent-scenario.json` with review links. Unit tests verify citation isolation, exact quotes, missing evidence and source integrity without model calls. Model results can vary: failures remain visible and must not be counted as successful investigation runs.

For a fresh interactive case, run `python3 agent_demo.py`, open its `reviewUrl`, enter a development reviewer ID such as `investigator-1`, and click **Run document review agent**. After findings appear, enter a review reason and choose **Accept findings and complete step** or **Return for rework**. Acceptance is disabled for findings that need more information. Supplying missing documents and starting a new run is explicit; the agent does not invent replacements.

The PostgreSQL queue uses `FOR UPDATE SKIP LOCKED`, ten-minute leases, fenced worker-result updates and up to three claims after process loss. A recovered run checks for an already persisted Playbook submission before repeating inference. Model invocation may repeat after a crash; it is not exactly once. Queue recovery and multiworker load still need failure/load testing. Human review state is authoritative in Playbook and reflected in the run when queried. External document retrieval, broader tools, autonomous closure, training, semantic correctness evaluation, production evidence storage and automatic Flowable job acquisition remain future work. Agent runs are currently explicitly requested for the selected policy step.
