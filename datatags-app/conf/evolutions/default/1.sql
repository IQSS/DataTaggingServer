# -- Generate the versioned policy model table

# -- !Ups
create table versioned_policy_model(
  id varchar(64) PRIMARY KEY,
  title varchar(1024),
  note text,
  created TIMESTAMP
)

# -- !Downs
drop table versioned_policy_model;

