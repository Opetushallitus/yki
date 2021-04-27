DO $$
BEGIN
  IF EXISTS(SELECT *
    FROM information_schema.columns
    WHERE table_name='evaluation_order' and column_name='phone_number')
  THEN
      ALTER TABLE "public"."evaluation_order" RENAME COLUMN "phone_number" TO "birthdate";
  END IF;
END $$;
