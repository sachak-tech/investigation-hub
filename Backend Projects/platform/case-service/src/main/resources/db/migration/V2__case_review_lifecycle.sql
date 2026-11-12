alter table investigation_case add column review_stage text;
alter table investigation_case add column assigned_to text;
alter table investigation_case add column last_review_actor_id text;
alter table investigation_case add column closed_at timestamptz;

create table case_action (
  action_id uuid primary key,
  case_id uuid not null references investigation_case(case_id),
  case_revision bigint not null,
  action text not null,
  from_stage text not null,
  to_stage text,
  actor_id text not null,
  assigned_to text,
  reason text not null,
  policy_id text not null,
  policy_version integer not null,
  created_at timestamptz not null default now(),
  unique (case_id, case_revision)
);
create index case_action_case_history on case_action(case_id, case_revision);
