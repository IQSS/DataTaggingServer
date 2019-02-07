# --- !Ups
alter table versioned_policy_models add column save_stat Boolean DEFAULT FALSE;
alter table versioned_policy_models add column note_opt Boolean DEFAULT FALSE;

# --- !Downs
alter table versioned_policy_models drop column note_opt;
alter table versioned_policy_models drop column save_stat;