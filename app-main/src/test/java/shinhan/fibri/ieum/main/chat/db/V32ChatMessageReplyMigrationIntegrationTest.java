package shinhan.fibri.ieum.main.chat.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V32ChatMessageReplyMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v32_chat_message_reply";

	private JdbcClient jdbc;

	@BeforeEach
	void recreatePreV32Database() {
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
	void v32KeepsReplyMessageWhenTheParentIsDeletedAndClearsOnlyTheReference() {
		applyMigration();
		long parentId = insertMessage("parent");
		long replyId = jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content, reply_to_message_id)
			VALUES (1, 1, 'reply', :parentId)
			RETURNING message_id
			""")
			.param("parentId", parentId)
			.query(Long.class)
			.single();

		jdbc.sql("DELETE FROM messages WHERE message_id = :parentId")
			.param("parentId", parentId)
			.update();

		assertThat(jdbc.sql("SELECT content FROM messages WHERE message_id = :replyId")
			.param("replyId", replyId)
			.query(String.class)
			.single()).isEqualTo("reply");
		assertThat(jdbc.sql("SELECT reply_to_message_id FROM messages WHERE message_id = :replyId")
			.param("replyId", replyId)
			.query(Long.class)
			.optional()).isEmpty();
	}

	@Test
	void canonicalSchemaDeclaresANullableSelfReferenceWithSetNullDeletion() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));

		Map<String, Object> column = jdbc.sql("""
			SELECT data_type, is_nullable, column_default
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'messages'
			  AND column_name = 'reply_to_message_id'
			""").query().singleRow();
		assertThat(column)
			.containsEntry("data_type", "bigint")
			.containsEntry("is_nullable", "YES")
			.containsEntry("column_default", null);
		assertThat(jdbc.sql("""
			SELECT confdeltype
			FROM pg_constraint
			WHERE conrelid = 'messages'::regclass
			  AND conname = 'fk_messages_reply_to_message'
			""").query(String.class).single()).isEqualTo("n");
	}

	private void applyMigration() {
		SqlScriptRunner.run(DATABASE, "migrations/v32_chat_message_reply.sql");
	}

	private long insertMessage(String content) {
		return jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content)
			VALUES (1, 1, :content)
			RETURNING message_id
			""")
			.param("content", content)
			.query(Long.class)
			.single();
	}

	private void seedMessageReferences() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, nickname)
			VALUES (1, 'reply-sender@example.com', 'reply-sender')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key)
			VALUES (1, 'direct', 'd:1:2')
			""").update();
	}
}
