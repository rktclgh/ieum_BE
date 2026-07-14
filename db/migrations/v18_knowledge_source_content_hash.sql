-- One-shot v17 -> v18 upgrade. Enforce canonical lowercase SHA-256 knowledge source hashes.
BEGIN;

DO $$
DECLARE
    malformed_sources TEXT;
BEGIN
    SELECT string_agg(identifier, ', ' ORDER BY source_id)
      INTO malformed_sources
      FROM (
          SELECT source_id,
                 COALESCE(NULLIF(btrim(external_ref), ''), 'source_id=' || source_id) AS identifier
            FROM public.knowledge_sources
           WHERE btrim(content_hash) !~ '^[0-9a-f]{64}$'
           ORDER BY source_id
           LIMIT 10
      ) malformed;

    IF malformed_sources IS NOT NULL THEN
        RAISE EXCEPTION
            'Malformed knowledge_sources.content_hash values block v18: %',
            malformed_sources;
    END IF;
END
$$;

ALTER TABLE public.knowledge_sources
    ADD CONSTRAINT ck_knowledge_sources_content_hash
    CHECK (btrim(content_hash) ~ '^[0-9a-f]{64}$') NOT VALID;

ALTER TABLE public.knowledge_sources
    VALIDATE CONSTRAINT ck_knowledge_sources_content_hash;

COMMIT;
