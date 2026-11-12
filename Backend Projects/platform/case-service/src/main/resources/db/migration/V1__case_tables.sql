create table if not exists subject_lock (
  tenant_id text not null,
  subject_id text not null,
  primary key (tenant_id, subject_id)
);

create table if not exists investigation_case (
  case_id uuid primary key,
  tenant_id text not null,
  subject_id text not null,
  case_type text not null,
  status text not null check (status in ('OPEN', 'CLOSED')),
  revision bigint not null check (revision >= 1),
  policy_id text not null,
  policy_version integer not null,
  required_steps jsonb not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists case_open_subject on investigation_case
  (tenant_id, subject_id, created_at desc) where status = 'OPEN';

create table if not exists case_alert (
  alert_id uuid primary key,
  case_id uuid not null references investigation_case(case_id),
  tenant_id text not null,
  alert_type text not null,
  detected_at timestamptz not null,
  linked_at timestamptz not null default now()
);
create index if not exists case_alert_case on case_alert(case_id);

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
create index if not exists case_outbox_pending on outbox_event(created_at)
  where delivered_at is null;
