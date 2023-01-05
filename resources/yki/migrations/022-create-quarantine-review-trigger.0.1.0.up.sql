CREATE OR REPLACE FUNCTION error_if_reviewed_quarantine_is_deleted() RETURNS TRIGGER AS $$
DECLARE
    existing_quarantine NUMERIC := (
        SELECT count(*) FROM quarantine
        WHERE id = NEW.quarantine_id
        AND deleted_at IS NULL
    );
BEGIN
    IF existing_quarantine > 0 THEN
        RETURN NEW;
    ELSE
        RAISE EXCEPTION 'Reviewed quarantine non-existing or deleted';
    END IF;
END;
$$ LANGUAGE plpgsql;
--;;
CREATE TRIGGER quarantine_review_quarantine_not_deleted_trigger
    BEFORE INSERT OR UPDATE
    ON quarantine_review
    FOR EACH ROW
EXECUTE PROCEDURE error_if_reviewed_quarantine_is_deleted();
