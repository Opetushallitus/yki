
-- Relocated at is used when a participant is relocated from exam session to another after registration
-- has ended for solki sync.
ALTER TABLE participant_sync_status ADD COLUMN IF NOT EXISTS relocated_at TIMESTAMP WITH TIME ZONE DEFAULT NULL;
