# -- Add trivial localization and affirmation to models

# -- !Ups
ALTER TABLE models ADD COLUMN require_affirmation BOOLEAN DEFAULT false;
ALTER TABLE models ADD COLUMN display_trivial_localization BOOLEAN DEFAULT false;

# -- !Downs
ALTER TABLE models DROP COLUMN require_affirmation;
ALTER TABLE models DROP COLUMN display_trivial_localization;
