package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V25WebPushSubscriptionsMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v25_web_push_subscriptions";

	private JdbcClient jdbc;

	@BeforeEach
	void recreateDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@Test
	void canonicalSchemaContainsWebPushSubscriptionTableIndexesAndTrigger() {
		SqlScriptRunner.run(DATABASE, "schema.sql");

		assertThat(tableExists("web_push_subscriptions")).isTrue();
		assertThat(columns("web_push_subscriptions")).containsExactly(
			"subscription_id",
			"user_id",
			"session_id",
			"endpoint",
			"endpoint_hash",
			"p256dh",
			"auth_secret",
			"binding_version",
			"expires_at",
			"created_at",
			"updated_at"
		);
		assertThat(indexNames("web_push_subscriptions")).contains(
			"idx_web_push_subscriptions_user",
			"uidx_web_push_subscriptions_session"
		);
		assertThat(indexNames("web_push_subscriptions"))
			.doesNotContain("idx_web_push_subscriptions_session");
		assertThat(indexIsUnique("uidx_web_push_subscriptions_session")).isTrue();
		assertThat(triggerNames("web_push_subscriptions")).contains("trg_web_push_subscriptions_updated");
	}

	@Test
	void migrationCreatesItsTriggerDependencyAndCanRunTwiceWithoutLosingData() {
		jdbc.sql("CREATE TABLE users (user_id BIGSERIAL PRIMARY KEY)").update();
		jdbc.sql("INSERT INTO users DEFAULT VALUES").update();

		SqlScriptRunner.run(DATABASE, "migrations/v25_web_push_subscriptions.sql");
		long subscriptionId = jdbc.sql("""
			INSERT INTO web_push_subscriptions (
			    user_id, session_id, endpoint, endpoint_hash, p256dh, auth_secret, updated_at
			)
			VALUES (1, 'session-1', 'https://push.example/1', :hash, 'key-1', 'auth-1', '2020-01-01T00:00:00Z')
			RETURNING subscription_id
			""")
			.param("hash", "a".repeat(64))
			.query(Long.class)
			.single();

		SqlScriptRunner.run(DATABASE, "migrations/v25_web_push_subscriptions.sql");
		jdbc.sql("UPDATE web_push_subscriptions SET p256dh = 'key-2' WHERE subscription_id = :subscriptionId")
			.param("subscriptionId", subscriptionId)
			.update();

		assertThat(tableExists("web_push_subscriptions")).isTrue();
		assertThat(triggerNames("web_push_subscriptions")).containsExactly("trg_web_push_subscriptions_updated");
		assertThat(jdbc.sql("SELECT p256dh FROM web_push_subscriptions WHERE subscription_id = :subscriptionId")
			.param("subscriptionId", subscriptionId)
			.query(String.class)
			.single()).isEqualTo("key-2");
		assertThat(jdbc.sql("SELECT updated_at > '2020-01-01T00:00:00Z' FROM web_push_subscriptions")
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void migrationDoesNotReplaceAnExistingSharedUpdatedAtFunction() {
		jdbc.sql("CREATE TABLE users (user_id BIGSERIAL PRIMARY KEY)").update();
		jdbc.sql("""
			CREATE FUNCTION public.set_updated_at()
			RETURNS TRIGGER AS $$
			BEGIN
			    NEW.updated_at = clock_timestamp();
			    RETURN NEW;
			END;
			$$ LANGUAGE plpgsql
			""").update();
		String originalDefinition = updatedAtFunctionDefinition();

		SqlScriptRunner.run(DATABASE, "migrations/v25_web_push_subscriptions.sql");

		assertThat(updatedAtFunctionDefinition()).isEqualTo(originalDefinition);
	}

	private boolean tableExists(String tableName) {
		return jdbc.sql("SELECT to_regclass(:tableName) IS NOT NULL")
			.param("tableName", tableName)
			.query(Boolean.class)
			.single();
	}

	private List<String> columns(String tableName) {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = :tableName
			ORDER BY ordinal_position
			""")
			.param("tableName", tableName)
			.query(String.class)
			.list();
	}

	private List<String> indexNames(String tableName) {
		return jdbc.sql("""
			SELECT indexname
			FROM pg_indexes
			WHERE schemaname = 'public' AND tablename = :tableName
			ORDER BY indexname
			""")
			.param("tableName", tableName)
			.query(String.class)
			.list();
	}

	private List<String> triggerNames(String tableName) {
		return jdbc.sql("""
			SELECT tgname
			FROM pg_trigger
			WHERE tgrelid = (:tableName)::regclass AND NOT tgisinternal
			ORDER BY tgname
			""")
			.param("tableName", tableName)
			.query(String.class)
			.list();
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

	private String updatedAtFunctionDefinition() {
		return jdbc.sql("SELECT pg_get_functiondef('public.set_updated_at()'::regprocedure)")
			.query(String.class)
			.single();
	}
}
