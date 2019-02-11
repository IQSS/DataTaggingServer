# --- Adds notes

# -- !Ups
create table notes (
  interview_history_id uuid,
  note text,
  node_id varchar(256),

  PRIMARY KEY (interview_history_id, node_id),
  FOREIGN KEY (interview_history_id) REFERENCES interview_history (key) ON DELETE CASCADE
);

# -- !Downs
drop table notes;