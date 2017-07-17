# --- Adds users

# -- !Ups

create table users (
  username varchar(64) PRIMARY KEY,
  name     varchar(512),
  email    varchar(256),
  orcid    varchar(36),
  url      varchar(512),
  encrypted_password varchar(1024)
);

# -- !Downs
drop table users;