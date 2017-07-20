# --- Add settings

# -- !Ups
create table settings (
  key VARCHAR(32) PRIMARY KEY,
  value text
);


# -- !Downs
drop table settings;

