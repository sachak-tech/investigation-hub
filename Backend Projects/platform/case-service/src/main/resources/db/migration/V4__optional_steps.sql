alter table investigation_case add column optional_steps jsonb not null default '[]'::jsonb;
