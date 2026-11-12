create table if not exists policy_definition (
  tenant_id text not null,
  policy_id text not null,
  version integer not null,
  status text not null check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
  document jsonb not null,
  published_at timestamptz,
  primary key (tenant_id, policy_id, version)
);

create unique index if not exists policy_one_published_per_tenant
  on policy_definition (tenant_id) where status = 'PUBLISHED';

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
