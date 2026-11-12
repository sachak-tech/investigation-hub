create table agent_submission (
  run_id uuid primary key,
  case_id uuid not null references playbook_instance(case_id),
  step_id text not null,
  task_id text not null,
  case_revision bigint not null,
  playbook_revision bigint not null,
  status text not null check (status in ('AWAITING_REVIEW', 'RETURNED', 'ACCEPTED')),
  submission jsonb not null,
  verified_citations jsonb not null,
  created_at timestamptz not null default now()
);
create unique index agent_one_pending_review_per_task on agent_submission(task_id) where status = 'AWAITING_REVIEW';
create index agent_submission_case on agent_submission(case_id, created_at);
create table agent_review (
  command_id uuid primary key,
  run_id uuid not null references agent_submission(run_id),
  actor_id text not null,
  request jsonb not null,
  receipt jsonb not null,
  reviewed_at timestamptz not null default now()
);
