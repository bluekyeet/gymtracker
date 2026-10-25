ALTER TABLE split_days DROP COLUMN IF EXISTS weekday;
ALTER TABLE split_days ADD COLUMN weekdays TEXT;
