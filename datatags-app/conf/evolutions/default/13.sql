# -- !Ups

ALTER TABLE policy_model_versions RENAME TO versions_md;
ALTER TABLE versions_md ADD running_status varchar(10) default 'Processing';
ALTER TABLE versions_md ADD messages text default '';
ALTER TABLE versions_md ADD visualizations text default '';
ALTER TABLE versions_md ADD pm_title text default '';
ALTER TABLE versions_md ADD pm_subtitle text default '';
ALTER TABLE versioned_policy_models RENAME TO models;
# -- !Downs

ALTER TABLE models RENAME TO versioned_policy_models;
ALTER TABLE versions_md DROP COLUMN pm_subtitle;
ALTER TABLE versions_md DROP COLUMN pm_title;
ALTER TABLE versions_md DROP COLUMN visualizations;
ALTER TABLE versions_md DROP COLUMN messages;
ALTER TABLE versions_md DROP COLUMN running_status;
ALTER TABLE versions_md RENAME TO policy_model_versions;