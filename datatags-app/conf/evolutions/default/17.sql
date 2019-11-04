# -- !Ups

ALTER TABLE versions_md ADD top_slots text default '';
ALTER TABLE versions_md ADD collapse_slots text default '';
ALTER TABLE versions_md ADD hidden_slots text default '';
ALTER TABLE versions_md ADD top_values text default '';

# -- !Downs

ALTER TABLE versions_md DROP COLUMN top_values;
ALTER TABLE versions_md DROP COLUMN hidden_slots;
ALTER TABLE versions_md DROP COLUMN collapse_slots;
ALTER TABLE versions_md DROP COLUMN top_slots;