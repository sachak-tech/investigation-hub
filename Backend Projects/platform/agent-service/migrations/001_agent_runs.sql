create table investigation_agent.agent_run (
  run_id uuid primary key,
  case_id uuid not null,
  step_id text not null,
  tenant_id text not null,
  requested_by text not null,
  request jsonb not null,
  status text not null check(status in ('QUEUED','RUNNING','AWAITING_REVIEW','FAILED','RETURNED','ACCEPTED')),
  model text not null,
  model_digest text not null,
  prompt_version text not null,
  attempts integer not null default 0,
  lease_token uuid,
  lease_until timestamptz,
  context_sha256 text,
  result jsonb,
  error_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index agent_queue on investigation_agent.agent_run(status, created_at);
