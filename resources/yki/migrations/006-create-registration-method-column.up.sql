CREATE TYPE registration_method AS ENUM ('SUOMIFI', 'EMAIL');

DO $$
    BEGIN
        BEGIN
            ALTER TABLE registration ADD COLUMN registration_method registration_method NULL;
        EXCEPTION
            WHEN duplicate_column THEN NULL;
        END;
    END;
$$