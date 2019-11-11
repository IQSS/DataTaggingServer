# -- !Ups

ALTER TABLE versions_md ADD list_display int default 6;

# -- !Downs

ALTER TABLE versions_md DROP COLUMN list_display;