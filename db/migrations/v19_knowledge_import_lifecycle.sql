-- One-shot v18 -> v19 upgrade. Enforce one active revision per logical knowledge source.
BEGIN;

DO $$
DECLARE
    duplicate_revisions TEXT;
BEGIN
    SELECT string_agg(identifier, ', ' ORDER BY identifier)
      INTO duplicate_revisions
      FROM (
          SELECT source_type::text || ':' || external_ref AS identifier
            FROM public.knowledge_sources
           WHERE active
             AND external_ref IS NOT NULL
           GROUP BY source_type, external_ref
          HAVING count(*) > 1
           ORDER BY source_type::text, external_ref
           LIMIT 10
      ) duplicates;

    IF duplicate_revisions IS NOT NULL THEN
        RAISE EXCEPTION
            'Duplicate active knowledge source revisions block v19: %',
            duplicate_revisions
            USING ERRCODE = '23505';
    END IF;
END
$$;

ALTER TABLE public.knowledge_sources
    ADD COLUMN ingestion_attempts SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_knowledge_sources_ingestion_attempts
        CHECK (ingestion_attempts >= 0);

DROP INDEX public.uidx_knowledge_source_external_hash;

CREATE UNIQUE INDEX uidx_knowledge_source_active_external_ref
    ON public.knowledge_sources(source_type, external_ref)
    WHERE active AND external_ref IS NOT NULL;

CREATE INDEX idx_knowledge_sources_active_anchor_location
    ON public.knowledge_sources USING gist(anchor_location)
    WHERE anchor_location IS NOT NULL AND active;

COMMIT;
