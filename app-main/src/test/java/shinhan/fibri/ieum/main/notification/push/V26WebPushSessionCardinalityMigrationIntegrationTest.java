package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V26WebPushSessionCardinalityMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v26_web_push_session_cardinality";

	private JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		jdbc.sql("CREATE TABLE users (user_id BIGSERIAL PRIMARY KEY)").update();
		jdbc.sql("INSERT INTO users DEFAULT VALUES").update();
		SqlScriptRunner.run(DATABASE, "migrations/v25_web_push_subscriptions.sql");
	}

	@Test
	void keepsOnlyTheMostRecentlyUpdatedSubscriptionPerSession() {
		insert("session-1", "old", "a", "2026-07-15T00:00:00Z");
		insert("session-1", "newer-low-id", "b", "2026-07-15T01:00:00Z");
		insert("session-1", "newer-high-id", "c", "2026-07-15T01:00:00Z");

		SqlScriptRunner.run(DATABASE, "migrations/v26_web_push_session_cardinality.sql");

		assertThat(jdbc.sql("SELECT endpoint FROM web_push_subscriptions WHERE session_id = 'session-1'")
			.query(String.class)
			.list()).containsExactly("https://push.example/newer-high-id");
	}

	@Test
	void createsUniqueSessionIndexAndDropsLegacyNonUniqueIndex() {
		SqlScriptRunner.run(DATABASE, "migrations/v26_web_push_session_cardinality.sql");

		assertThat(indexIsUnique("uidx_web_push_subscriptions_session")).isTrue();
		assertThat(indexExists("idx_web_push_subscriptions_session")).isFalse();
	}

	@Test
	void canRunTwiceWithoutChangingTheChosenSurvivor() {
		insert("session-1", "old", "a", "2026-07-15T00:00:00Z");
		long survivor = insert("session-1", "new", "b", "2026-07-15T01:00:00Z");

		SqlScriptRunner.run(DATABASE, "migrations/v26_web_push_session_cardinality.sql");
		SqlScriptRunner.run(DATABASE, "migrations/v26_web_push_session_cardinality.sql");

		assertThat(jdbc.sql("SELECT subscription_id FROM web_push_subscriptions")
			.query(Long.class)
			.list()).containsExactly(survivor);
	}

	@Test
	void rejectsASecondSubscriptionForTheSameSession() {
		SqlScriptRunner.run(DATABASE, "migrations/v26_web_push_session_cardinality.sql");
		insert("session-1", "first", "a", "2026-07-15T00:00:00Z");

		assertThatThrownBy(() -> insert("session-1", "second", "b", "2026-07-15T01:00:00Z"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insert(String sessionId, String endpointSuffix, String hashPrefix, String updatedAt) {
		return jdbc.sql("""
			INSERT INTO web_push_subscriptions (
			    user_id, session_id, endpoint, endpoint_hash, p256dh, auth_secret, updated_at
			)
			VALUES (1, :sessionId, :endpoint, :endpointHash, 'key', 'auth', CAST(:updatedAt AS timestamptz))
			RETURNING subscription_id
			""")
			.param("sessionId", sessionId)
			.param("endpoint", "https://push.example/" + endpointSuffix)
			.param("endpointHash", hashPrefix.repeat(64))
			.param("updatedAt", updatedAt)
			.query(Long.class)
			.single();
	}

	private boolean indexExists(String indexName) {
		return Boolean.TRUE.equals(jdbc.sql("SELECT to_regclass(:indexName) IS NOT NULL")
			.param("indexName", indexName)
			.query(Boolean.class)
			.single());
	}

	private boolean indexIsUnique(String indexName) {
		return Boolean.TRUE.equals(jdbc.sql("""
			SELECT pg_index.indisunique
			FROM pg_index
			JOIN pg_class ON pg_class.oid = pg_index.indexrelid
			WHERE pg_class.relname = :indexName
			""")
			.param("indexName", indexName)
			.query(Boolean.class)
			.single());
	}
}
