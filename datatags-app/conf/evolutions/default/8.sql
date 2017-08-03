create table uuid_for_forgot_password(
  username varchar(64) PRIMARY KEY,
  uuid    varchar(64),
  reset_password_date TIMESTAMP,

  FOREIGN KEY (username) REFERENCES users(username)
);

drop table uuid_for_forgot_password;