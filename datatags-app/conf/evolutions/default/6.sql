# --- Adds comments

# -- !Ups

create table comments (
  writer varchar(64),
  comment varchar(1024),
  version_policy_model_id char(64),
  localization char(64),
  version Integer,
  target_type char(64),
  target_content char(64),
  resolved boolean,
  time TIMESTAMP,
  id serial PRIMARY KEY

);

# -- !Downs
drop table comments;