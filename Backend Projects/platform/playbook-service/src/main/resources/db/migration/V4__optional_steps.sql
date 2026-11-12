alter table playbook_instance add column optional_steps jsonb not null default '[]'::jsonb;
