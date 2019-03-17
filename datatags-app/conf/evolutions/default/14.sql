# -- change char to varchar

# -- !Ups
ALTER TABLE comments alter column version_policy_model_id TYPE VARCHAR(64);
ALTER TABLE comments alter column localization TYPE VARCHAR(64);
ALTER TABLE comments alter column target_type TYPE VARCHAR(64);
ALTER TABLE comments alter column target_content TYPE VARCHAR(64);

# -- !Downs
ALTER TABLE comments alter column target_content TYPE CHAR(64);
ALTER TABLE comments alter column target_type TYPE CHAR(64);
ALTER TABLE comments alter column localization TYPE CHAR(64);
ALTER TABLE comments alter column version_policy_model_id TYPE CHAR(64);
