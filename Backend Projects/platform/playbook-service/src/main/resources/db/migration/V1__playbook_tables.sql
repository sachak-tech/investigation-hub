create table if not exists playbook_case_lock (
  case_id uuid primary key
);

create table if not exists playbook_instance (
  case_id uuid primary key,
  tenant_id text not null,
  flowable_case_instance_id text not null unique,
  case_revision bigint not null,
  playbook_revision bigint not null,
  case_type text not null,
  policy_id text not null,
  policy_version integer not null,
  required_steps jsonb not null,
  last_event_id uuid not null,
  updated_at timestamptz not null default now()
);

create table if not exists outbox_event (
  event_id uuid primary key,
  event_type text not null,
  tenant_id text not null,
  aggregate_id text not null,
  aggregate_revision bigint not null,
  correlation_id text not null,
  actor_id text not null,
  payload jsonb not null,
  created_at timestamptz not null default now(),
  delivered_at timestamptz,
  attempts integer not null default 0,
  last_error text
);
