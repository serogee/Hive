-- The name that the person chose. Null means the name from the sign-in, which display_name keeps.
ALTER TABLE hive.accounts ADD COLUMN chosen_name TEXT;
