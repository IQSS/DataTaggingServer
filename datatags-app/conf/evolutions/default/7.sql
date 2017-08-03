# -- Add invitations TABLE

# -- !Ups
create table uuid_for_invitation(
  uuid varchar(64) PRIMARY KEY
);

# -- !Downs
drop table uuid_for_invitation;