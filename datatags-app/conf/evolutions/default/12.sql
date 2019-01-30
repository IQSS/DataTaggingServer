# -- !Ups

ALTER TABLE comments ADD CONSTRAINT comments_fkey FOREIGN KEY (version, version_policy_model_id) REFERENCES policy_model_versions(version_num, model_id) on DELETE CASCADE ;

# -- !Downs

ALTER TABLE comments DROP CONSTRAINT comments_fkey;