create table dynamic_policy_step (
  case_id uuid not null references playbook_instance(case_id),
  step_id text not null,
  plan_item_instance_id text not null,
  name text not null,
  destination_id text not null,
  primary key (case_id, step_id),
  unique (plan_item_instance_id)
);
