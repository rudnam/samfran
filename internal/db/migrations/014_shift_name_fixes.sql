-- Names the photo reader got wrong (internal/shift): when someone fixes a
-- coworker's name while checking a scan, the misreading is remembered, so the
-- next scan says the right name. Both are names as printed on the sheet.
CREATE TABLE shift_name_fixes (
    space_id   BIGINT NOT NULL REFERENCES spaces (id) ON DELETE CASCADE,
    misread    TEXT NOT NULL,
    name       TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id, misread)
);
