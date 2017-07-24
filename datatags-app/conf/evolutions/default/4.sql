# -- Add link to policy model versions

# --- !Ups
alter table policy_model_versions add column access_link VARCHAR(42);

create index by_link on policy_model_versions (access_link);

update policy_model_versions set access_link=CONCAT(model_id,'-',version_num);

# --- !Downs
alter table policy_model_versions drop column access_link;
