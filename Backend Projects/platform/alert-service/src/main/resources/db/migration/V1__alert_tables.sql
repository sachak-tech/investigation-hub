create table if not exists alert (
  alert_id uuid primary key,
  accepted_event_id uuid not null unique,
  tenant_id text not null,
  source_system text not null,
  source_alert_id text not null,
  alert_type text not null,
  subject_id text not null,
  subject_verified boolean not null,
  detected_at timestamptz not null,
  created_at timestamptz not null default now(),
  unique (tenant_id, source_system, source_alert_id)
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
create index if not exists alert_outbox_pending on outbox_event(created_at)
  where delivered_at is null;
