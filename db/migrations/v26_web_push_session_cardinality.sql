BEGIN;

-- Serialize migration cleanup/index creation with subscription DML.
LOCK TABLE web_push_subscriptions IN SHARE ROW EXCLUSIVE MODE;

-- Before the invariant existed, one session could own multiple endpoints.
-- The last updated row is the authoritative registration; subscription_id is
-- the deterministic tie-breaker.
WITH ranked AS (
    SELECT subscription_id,
           row_number() OVER (
               PARTITION BY session_id
               ORDER BY updated_at DESC, subscription_id DESC
           ) AS row_rank
    FROM web_push_subscriptions
)
DELETE FROM web_push_subscriptions AS subscription
USING ranked
WHERE subscription.subscription_id = ranked.subscription_id
  AND ranked.row_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_web_push_subscriptions_session
    ON web_push_subscriptions(session_id);

DROP INDEX IF EXISTS idx_web_push_subscriptions_session;

COMMIT;
