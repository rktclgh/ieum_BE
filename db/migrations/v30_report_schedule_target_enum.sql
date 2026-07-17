-- This must be committed before v31 references the new enum value in constraints or trigger bodies.
ALTER TYPE public.report_target_type ADD VALUE IF NOT EXISTS 'schedule';
