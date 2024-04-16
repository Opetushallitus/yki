CREATE TABLE cas_oppija_ticketstore (
  ticket TEXT PRIMARY KEY,
  logged_in TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
