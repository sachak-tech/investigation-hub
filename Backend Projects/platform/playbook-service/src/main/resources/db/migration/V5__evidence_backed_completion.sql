create table investigation_document (
  document_id uuid primary key,
  case_id uuid not null references playbook_instance(case_id),
  tenant_id text not null,
  title text not null,
  source_reference text not null,
  content text not null,
  sha256 text not null,
  registered_by text not null,
  registered_at timestamptz not null default now()
);
create index investigation_document_case on investigation_document(case_id);

create table step_completion (
  command_id uuid primary key,
  case_id uuid not null references playbook_instance(case_id),
  step_id text not null,
  task_id text not null unique,
  actor_id text not null,
  request jsonb not null,
  receipt jsonb not null,
  completed_at timestamptz not null default now()
);
create index step_completion_case on step_completion(case_id, completed_at);
