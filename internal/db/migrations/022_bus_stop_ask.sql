-- The bus-stop alarm (internal/bus): whether the Android app asks "Start
-- bus alarm?" when a shift whose stop this is ends. On for every stop until
-- switched off for one; the question as a whole is switched on per phone.
ALTER TABLE bus_saved_stops ADD COLUMN ask BOOLEAN NOT NULL DEFAULT true;
