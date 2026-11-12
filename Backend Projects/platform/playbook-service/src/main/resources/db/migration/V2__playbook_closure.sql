alter table playbook_instance add column status text not null default 'OPEN'
  check (status in ('OPEN', 'CLOSED'));
alter table playbook_instance add column closed_at timestamptz;
