# --- Adds notes

# -- !Ups
create table notes (
  uuid uuid,
  note text,
  node_id char(64),

  PRIMARY KEY (uuid, node_id),
  FOREIGN KEY (uuid) REFERENCES interview_history (key) ON DELETE CASCADE
);

# -- !Downs
drop table notes;