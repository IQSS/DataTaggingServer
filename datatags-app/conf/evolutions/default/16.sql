-- Make notes standalone, rather than dependant on interview statistics

# -- !Ups
ALTER TABLE notes DROP constraint "notes_interview_history_id_fkey";
ALTER TABLE notes ADD COLUMN time TIMESTAMP;



# -- !Downs

ALTER TABLE notes add constraint FOREIGN KEY (interview_history_id) REFERENCES interview_history (key) ON DELETE CASCADE
ALTER TABLE notes DROP COLUMN time;
