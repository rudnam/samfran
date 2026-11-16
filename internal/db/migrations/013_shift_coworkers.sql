-- Who else is on the same shift (internal/shift): the names, as printed on
-- the sheet, of the people with exactly the same code that day. Only kept
-- for the days the person works; nothing else about them is stored.
ALTER TABLE shifts ADD COLUMN coworkers TEXT[] NOT NULL DEFAULT '{}';
