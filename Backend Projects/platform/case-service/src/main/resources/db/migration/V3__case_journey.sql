create table case_journey_event (
  event_id uuid primary key,
  case_id uuid not null references investigation_case(case_id),
  case_revision bigint not null,
  event_type text not null,
  actor_id text not null,
  payload jsonb not null,
  occurred_at timestamptz not null default now()
);
create index case_journey_case_history on case_journey_event(case_id, case_revision, occurred_at);

insert into case_journey_event
  (event_id, case_id, case_revision, event_type, actor_id, payload, occurred_at)
select event_id, aggregate_id::uuid, aggregate_revision, event_type, actor_id, payload, created_at
from outbox_event
where event_type in ('CaseAlertLinked', 'CaseClassificationChanged', 'CaseRouted', 'CaseClosed')
on conflict (event_id) do nothing;
