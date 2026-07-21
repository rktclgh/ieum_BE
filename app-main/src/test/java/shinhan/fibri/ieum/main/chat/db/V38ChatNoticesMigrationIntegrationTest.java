package shinhan.fibri.ieum.main.chat.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V38ChatNoticesMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v38_chat_notices";

	private JdbcClient jdbc;

	@BeforeEach
	void recreatePreV38Database() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "test-baselines/schema-v12.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedMessageReferences();
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void v38CreatesChatNoticesAndPinnedNoticeReference() {
		applyMigration();
		long noticeId = jdbc.sql("""
			INSERT INTO chat_notices (room_id, message_id, created_by)
			VALUES (1, 1, 1)
			RETURNING notice_id
			""").query(Long.class).single();

		jdbc.sql("""
			UPDATE chat_rooms
			SET pinned_notice_id = :noticeId
			WHERE room_id = 1
			""")
			.param("noticeId", noticeId)
			.update();

		assertThat(jdbc.sql("SELECT pinned_notice_id FROM chat_rooms WHERE room_id = 1")
			.query(Long.class)
			.single()).isEqualTo(noticeId);

		jdbc.sql("DELETE FROM chat_notices WHERE notice_id = :noticeId")
			.param("noticeId", noticeId)
			.update();

		assertThat(jdbc.sql("SELECT pinned_notice_id FROM chat_rooms WHERE room_id = 1")
			.query(Long.class)
			.optional()).isEmpty();
	}

	@Test
	void canonicalSchemaDeclaresChatNoticeContracts() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));

		assertThat(columns("chat_notices")).contains(
			"notice_id",
			"room_id",
			"message_id",
			"created_by",
			"created_at"
		);
		assertThat(columns("chat_rooms")).contains("pinned_notice_id");
		assertThat(indexNames("chat_notices")).contains(
			"uidx_chat_notices_room_message",
			"idx_chat_notices_room_created"
		);
		assertThat(foreignKeyDeleteActions("chat_notices")).containsAllEntriesOf(Map.of(
			"fk_chat_notices_room", "c",
			"fk_chat_notices_message", "c",
			"fk_chat_notices_created_by", "n"
		));
		assertThat(foreignKeyDeleteActions("chat_rooms"))
			.containsEntry("fk_chat_rooms_pinned_notice", "n");
	}

	private void applyMigration() {
		SqlScriptRunner.run(DATABASE, "migrations/v38_chat_notices.sql");
	}

	private void seedMessageReferences() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, nickname)
			VALUES (1, 'notice-sender@example.com', 'notice-sender')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key)
			VALUES (1, 'direct', 'd:1:2')
			""").update();
		jdbc.sql("""
			INSERT INTO messages (message_id, room_id, sender_id, content)
			VALUES (1, 1, 1, 'notice source')
			""").update();
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

	private Map<String, String> foreignKeyDeleteActions(String tableName) {
		return jdbc.sql("""
			SELECT conname, confdeltype
			FROM pg_constraint
			WHERE conrelid = (:tableName)::regclass
			  AND contype = 'f'
			ORDER BY conname
			""")
			.param("tableName", tableName)
			.query((rs, rowNum) -> Map.entry(rs.getString("conname"), rs.getString("confdeltype")))
			.list()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}
}
