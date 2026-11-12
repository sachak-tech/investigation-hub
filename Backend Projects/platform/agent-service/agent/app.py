import hashlib
import json
import logging
import os
import threading
from contextlib import asynccontextmanager
from uuid import UUID
import httpx
from fastapi import FastAPI, Header, HTTPException
from . import store
from .model import MODEL, PROMPT_VERSION, ModelOutputError, analyse, model_metadata
from .models import RunRequest

CASE_URL = os.getenv("IH_CASE_URL", "http://127.0.0.1:18082").rstrip("/")
PLAYBOOK_URL = os.getenv("IH_PLAYBOOK_URL", "http://127.0.0.1:18083").rstrip("/")
AGENT_ACTOR = "agent:bank-document-review"
logger = logging.getLogger("investigation-agent")


def backend(method, base, path, tenant, actor=AGENT_ACTOR, body=None):
    with httpx.Client(timeout=30, trust_env=False) as client:
        response = client.request(method, base + path, json=body,
                                  headers={"X-Tenant-Id": tenant, "X-Actor-Id": actor, "X-Correlation-Id": "bank-document-agent"})
        response.raise_for_status()
        return response.json()


def context(case_id, step_id, tenant):
    return backend("GET", PLAYBOOK_URL, f"/internal/playbooks/cases/{case_id}/steps/{step_id}/agent-context", tenant)


def submissions(case_id, step_id, tenant):
    return backend("GET", PLAYBOOK_URL, f"/v1/playbooks/cases/{case_id}/steps/{step_id}/agent-findings", tenant)


def execute(run):
    case_id, step_id, tenant = run["case_id"], run["step_id"], run["tenant_id"]
    try:
        existing = next((item for item in submissions(case_id, step_id, tenant) if item["runId"] == str(run["run_id"])), None)
        if existing:
            store.finish(run, existing["status"], {"submission": existing, "modelCalls": 0, "recovered": True})
            return
        ctx = context(case_id, step_id, tenant)
        request = run["request"]
        if ctx["caseRevision"] != request["expectedCaseRevision"] or ctx["playbookRevision"] != request["expectedPlaybookRevision"]:
            store.finish(run, "FAILED", error="STALE_CONTEXT")
            return
        if ctx.get("pendingRunId"):
            store.finish(run, "FAILED", error="REVIEW_ALREADY_PENDING")
            return
        if model_metadata()["digest"] != run["model_digest"] or run["model"] != MODEL:
            store.finish(run, "FAILED", error="MODEL_CHANGED_SINCE_ENQUEUE")
            return
        digest = hashlib.sha256(json.dumps(ctx, sort_keys=True).encode()).hexdigest()
        analysis, calls = analyse(ctx)
        proposal = {"runId": str(run["run_id"]), "expectedCaseRevision": ctx["caseRevision"],
                    "expectedPlaybookRevision": ctx["playbookRevision"], "taskId": ctx["taskId"],
                    "modelProvider": "OLLAMA", "model": run["model"], "promptVersion": PROMPT_VERSION,
                    **analysis.model_dump(mode="json")}
        accepted = backend("POST", CASE_URL, f"/v1/cases/{case_id}/steps/{step_id}/agent-findings", tenant, body=proposal)
        store.finish(run, "AWAITING_REVIEW", {"submission": accepted, "modelCalls": calls}, context_hash=digest)
    except ModelOutputError as error:
        store.finish(run, "FAILED", result={"validationErrors": error.errors, "modelCalls": len(error.errors)}, error="MODEL_OUTPUT_FAILED_VALIDATION")
    except httpx.HTTPStatusError as error:
        store.finish(run, "FAILED", error="DEPENDENCY_HTTP_" + str(error.response.status_code))
    except (ValueError, RuntimeError):
        store.finish(run, "FAILED", error="MODEL_OR_SOURCE_VALIDATION_FAILED")
    except httpx.HTTPError:
        store.finish(run, "FAILED", error="DEPENDENCY_UNAVAILABLE")
    except Exception:
        logger.exception("Agent run failed; run_id=%s", run["run_id"])
        store.finish(run, "FAILED", error="INTERNAL_ERROR")


def work(stop):
    while not stop.is_set():
        try:
            run = store.claim()
            if run:
                execute(run)
                continue
        except Exception:
            logger.exception("Agent queue operation failed")
        stop.wait(1)


@asynccontextmanager
async def lifespan(app):
    store.migrate()
    stop = threading.Event()
    worker = threading.Thread(target=work, args=(stop,), daemon=True)
    worker.start()
    yield
    stop.set()
    worker.join(timeout=5)


app = FastAPI(title="Investigation Hub Agent API", version="0.1.0", lifespan=lifespan)


@app.get("/actuator/health/readiness")
def readiness():
    with store.connect() as connection:
        connection.execute("select 1")
    try:
        metadata = model_metadata()
        return {"status": "UP", "modelAvailable": True, "provider": "OLLAMA", **metadata}
    except Exception:
        return {"status": "UP", "modelAvailable": False, "provider": "OLLAMA", "model": MODEL}


def public(run):
    return {"runId": run["run_id"], "caseId": run["case_id"], "stepId": run["step_id"],
            "status": run["status"], "modelProvider": "OLLAMA", "model": run["model"],
            "modelDigest": run["model_digest"], "promptVersion": run["prompt_version"],
            "attempts": run["attempts"], "contextSha256": run["context_sha256"],
            "result": run["result"], "errorCode": run["error_code"], "createdAt": run["created_at"], "updatedAt": run["updated_at"]}


def matching(run, request, actor):
    if run is None or run["request"] != request.model_dump(mode="json") or run["requested_by"] != actor:
        raise HTTPException(409, "Run ID reused with different request details")
    return public(run)


@app.post("/v1/agent-runs", status_code=202)
def enqueue(request: RunRequest, x_tenant_id: str = Header(), x_actor_id: str = Header()):
    if not x_tenant_id.strip() or not x_actor_id.strip():
        raise HTTPException(400, "Tenant and actor are required")
    existing = store.get(request.runId, x_tenant_id)
    if existing:
        return matching(existing, request, x_actor_id)
    try:
        ctx = context(request.caseId, request.stepId, x_tenant_id)
        if ctx["caseRevision"] != request.expectedCaseRevision or ctx["playbookRevision"] != request.expectedPlaybookRevision:
            raise HTTPException(409, "Requested agent context is stale")
        if ctx.get("pendingRunId"):
            raise HTTPException(409, "An agent result is already awaiting human review")
        metadata = model_metadata()
    except httpx.HTTPStatusError as error:
        raise HTTPException(error.response.status_code, "Case or step is unavailable for this agent") from error
    except (httpx.HTTPError, RuntimeError) as error:
        raise HTTPException(503, "Ollama model or backend is unavailable; check service configuration") from error
    run = store.enqueue(request, x_tenant_id, x_actor_id, metadata, PROMPT_VERSION)
    return matching(run, request, x_actor_id)


@app.get("/v1/agent-runs/{run_id}")
def get_run(run_id: UUID, x_tenant_id: str = Header()):
    run = store.get(run_id, x_tenant_id)
    if run is None:
        raise HTTPException(404, "Agent run not found")
    if run["status"] == "AWAITING_REVIEW":
        try:
            item = next((item for item in submissions(run["case_id"], run["step_id"], x_tenant_id)
                         if item["runId"] == str(run_id)), None)
            if item and item["status"] in ("RETURNED", "ACCEPTED"):
                store.reviewed(run_id, x_tenant_id, item["status"])
                run = store.get(run_id, x_tenant_id)
        except httpx.HTTPError:
            pass  # Persisted result remains readable when a dependency is temporarily unavailable.
    return public(run)
