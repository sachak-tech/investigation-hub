import os
from pathlib import Path
from uuid import UUID, uuid4
import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


def connect():
    return psycopg.connect(host=os.getenv("IH_DB_HOST", "localhost"), port=int(os.getenv("IH_DB_PORT", "5432")),
                          dbname=os.getenv("IH_DB_NAME", "postgres"), user=os.getenv("IH_DB_USER", "postgres"),
                          password=os.environ["IH_DB_PASSWORD"], row_factory=dict_row)


def migrate():
    with connect() as connection:
        connection.execute("select pg_advisory_xact_lock(hashtext('investigation_agent_schema'))")
        connection.execute("create schema if not exists investigation_agent")
        connection.execute("create table if not exists investigation_agent.schema_migration(version integer primary key)")
        if connection.execute("select 1 from investigation_agent.schema_migration where version = 1").fetchone() is None:
            connection.execute((Path(__file__).parents[1] / "migrations/001_agent_runs.sql").read_text())
            connection.execute("insert into investigation_agent.schema_migration values (1)")


def get(run_id: UUID, tenant: str):
    with connect() as connection:
        return connection.execute("select * from investigation_agent.agent_run where run_id=%s and tenant_id=%s",
                                  (run_id, tenant)).fetchone()


def enqueue(request, tenant: str, actor: str, metadata: dict, prompt_version: str):
    with connect() as connection:
        connection.execute("""insert into investigation_agent.agent_run
            (run_id, case_id, step_id, tenant_id, requested_by, request, status, model, model_digest, prompt_version)
            values (%s,%s,%s,%s,%s,%s,'QUEUED',%s,%s,%s) on conflict(run_id) do nothing""",
                           (request.runId, request.caseId, request.stepId, tenant, actor,
                            Jsonb(request.model_dump(mode="json")), metadata["model"], metadata["digest"], prompt_version))
    return get(request.runId, tenant)


def claim():
    with connect() as connection:
        connection.execute("""update investigation_agent.agent_run set status='FAILED', error_code='WORKER_LEASE_EXHAUSTED',
            updated_at=now() where status='RUNNING' and lease_until<now() and attempts>=3""")
        return connection.execute("""with candidate as (
            select run_id from investigation_agent.agent_run
            where (status='QUEUED' or (status='RUNNING' and lease_until<now())) and attempts<3
            order by created_at for update skip locked limit 1)
            update investigation_agent.agent_run r set status='RUNNING', attempts=r.attempts+1,
            lease_token=%s, lease_until=now()+interval '10 minutes', updated_at=now()
            from candidate where r.run_id=candidate.run_id returning r.*""", (uuid4(),)).fetchone()


def finish(run: dict, status: str, result=None, error=None, context_hash=None):
    with connect() as connection:
        connection.execute("""update investigation_agent.agent_run set status=%s, result=%s, error_code=%s,
            context_sha256=coalesce(%s,context_sha256), lease_until=null, updated_at=now()
            where run_id=%s and status='RUNNING' and lease_token=%s""",
                           (status, Jsonb(result) if result is not None else None, error, context_hash, run["run_id"], run["lease_token"]))


def reviewed(run_id, tenant, status):
    with connect() as connection:
        connection.execute("""update investigation_agent.agent_run set status=%s, updated_at=now()
            where run_id=%s and tenant_id=%s and status='AWAITING_REVIEW'""", (status, run_id, tenant))
