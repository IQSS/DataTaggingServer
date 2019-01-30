# -- add statistics tables

# -- !Ups
create table interview_history(
  key uuid PRIMARY KEY,
  version_num INT,
  model_id varchar(64),
  loc VARCHAR(64),
  path VARCHAR(32),
  agent VARCHAR(128),

  FOREIGN KEY (version_num, model_id) REFERENCES policy_model_versions(version_num, model_id)
);

create table interview_history_records(
  interview_history_key uuid,
  time TIMESTAMP,
  action VARCHAR(64),

  FOREIGN KEY (interview_history_key) REFERENCES interview_history (key) ON DELETE CASCADE
);

# -- !Downs
drop table interview_history_records;
drop table interview_history;
