# -- add forgot password link table

# -- !Ups
create table uuid_for_forgot_password(
  username varchar(64),
  uuid    varchar(64),
  reset_password_date TIMESTAMP,

  PRIMARY KEY (username, uuid),
  FOREIGN KEY (username) REFERENCES users(username)
);

# -- !Downs
drop table uuid_for_forgot_password;