# -- Add policy model versions

# -- !Ups
create table policy_model_versions (
  version_num INT,
  model_id varchar(64),
  publication_status varchar(9),
  commenting_status varchar(17),
  last_update TIMESTAMP,
  note TEXT,

  PRIMARY KEY (version_num, model_id),
  FOREIGN KEY (model_id) REFERENCES versioned_policy_models (id)
);

create index by_model on policy_model_versions (model_id);

# -- !Downs
drop table policy_model_versions;