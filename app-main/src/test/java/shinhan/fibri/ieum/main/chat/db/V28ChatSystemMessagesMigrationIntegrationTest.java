package shinhan.fibri.ieum.main.chat.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.MessageType;

@Testcontainers(disabledWithoutDocker = true)
class V28ChatSystemMessagesMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v28_chat_system_messages";

	private JdbcClient jdbc;

	@BeforeEach
	void recreatePreV28Database() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "test-baselines/schema-v12.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedLegacyMessageReferences();
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void v28PreservesExistingMessageRowsAsUserMessages() {
		long messageId = jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content)
			VALUES (1, 1, 'legacy user message')
			RETURNING message_id
			""")
			.query(Long.class)
			.single();

		applyMigration();

		assertThat(jdbc.sql("SELECT count(*) FROM messages")
			.query(Long.class)
			.single()).isEqualTo(1L);
		assertThat(jdbc.sql("SELECT sender_id FROM messages WHERE message_id = :messageId")
			.param("messageId", messageId)
			.query(Long.class)
			.single()).isEqualTo(1L);
		assertThat(jdbc.sql("SELECT content FROM messages WHERE message_id = :messageId")
			.param("messageId", messageId)
			.query(String.class)
			.single()).isEqualTo("legacy user message");
		assertThat(jdbc.sql("SELECT message_type FROM messages WHERE message_id = :messageId")
			.param("messageId", messageId)
			.query(String.class)
			.single()).isEqualTo("user");
	}

	@Test
	void v28AllowsTextOnlySystemMessagesAndRejectsInvalidKinds() {
		applyMigration();

		long systemMessageId = jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content, message_type)
			VALUES (1, 1, 'member left the meeting', 'system')
			RETURNING message_id
			""")
			.query(Long.class)
			.single();
		assertThat(jdbc.sql("SELECT message_type FROM messages WHERE message_id = :messageId")
			.param("messageId", systemMessageId)
			.query(String.class)
			.single()).isEqualTo("system");

		UUID imageFileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		jdbc.sql("""
			INSERT INTO files (file_id, uploader_id, s3_key)
			VALUES (:fileId, 1, 'messages/system-image')
			""")
			.param("fileId", imageFileId)
			.update();

		assertPostgresConstraintViolation(() -> jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content, image_file_id, message_type)
			VALUES (1, 1, 'must not have an image', :fileId, 'system')
			""")
			.param("fileId", imageFileId)
			.update(), "23514");
		assertPostgresConstraintViolation(() -> jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content, message_type)
			VALUES (1, 1, 'invalid type', 'unexpected')
			""").update(), "23514");
		assertPostgresConstraintViolation(() -> jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content, message_type)
			VALUES (1, 1, 'missing type', NULL)
			""").update(), "23502");
	}

	@Test
	void canonicalSchemaMatchesV28MessageTypeContract() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));

		Map<String, Object> column = jdbc.sql("""
			SELECT data_type, is_nullable, column_default
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'messages'
			  AND column_name = 'message_type'
			""").query().singleRow();

		assertThat(column)
			.containsEntry("data_type", "character varying")
			.containsEntry("is_nullable", "NO");
		assertThat(String.valueOf(column.get("column_default"))).contains("user");
		assertThat(constraintDefinition("ck_messages_message_type"))
			.contains("message_type")
			.contains("user")
			.contains("system");
		assertThat(constraintDefinition("ck_messages_system_text_only"))
			.contains("message_type")
			.contains("system")
			.contains("content IS NOT NULL")
			.contains("image_file_id IS NULL");
	}

	@Test
	void messageFactoriesAssignUserAndSystemTypesAndKeepSystemSender() throws NoSuchFieldException {
		ChatRoom room = ChatRoom.group(1L);
		User sender = user();
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-16T10:00:00+09:00");

		assertThat(Message.text(room, sender, "user text", createdAt).getMessageType())
			.isEqualTo(MessageType.user);
		assertThat(Message.image(room, sender, UUID.fromString("22222222-2222-2222-2222-222222222222"), createdAt)
			.getMessageType()).isEqualTo(MessageType.user);

		Message systemMessage = Message.system(room, sender, "member left", createdAt);
		assertThat(systemMessage.getMessageType()).isEqualTo(MessageType.system);
		assertThat(systemMessage.getSender()).isSameAs(sender);
		assertThat(systemMessage.getContent()).isEqualTo("member left");
		assertThat(systemMessage.getImageFileId()).isNull();
		assertThat(systemMessage.getCreatedAt()).isEqualTo(createdAt);
		assertThat(Message.class.getDeclaredField("messageType").getAnnotation(Enumerated.class).value())
			.isEqualTo(EnumType.STRING);
	}

	@Test
	void systemMessageRejectsBlankContentAndMissingSender() {
		ChatRoom room = ChatRoom.group(1L);
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-16T10:00:00+09:00");

		assertThatThrownBy(() -> Message.system(room, user(), " ", createdAt))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("system content must not be blank");
		assertThatThrownBy(() -> Message.system(room, null, "member left", createdAt))
			.isInstanceOf(NullPointerException.class)
			.hasMessage("sender must not be null");
	}

	private void applyMigration() {
		SqlScriptRunner.run(DATABASE, "migrations/v28_chat_system_messages.sql");
	}

	private void assertPostgresConstraintViolation(
		org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
		String expectedSqlState
	) {
		Throwable failure = catchThrowable(action);

		assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(failure.getCause()).isInstanceOf(SQLException.class);
		assertThat(((SQLException) failure.getCause()).getSQLState()).isEqualTo(expectedSqlState);
	}

	private String constraintDefinition(String constraintName) {
		return jdbc.sql("""
			SELECT pg_get_constraintdef(oid)
			FROM pg_constraint
			WHERE conrelid = 'messages'::regclass
			  AND conname = :constraintName
			""")
			.param("constraintName", constraintName)
			.query(String.class)
			.single();
	}

	private User user() {
		return User.createEmailUser(
			"message-sender@example.com",
			"password-hash",
			"message-sender",
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
	}

	private void seedLegacyMessageReferences() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, nickname)
			VALUES (1, 'message-sender@example.com', 'message-sender')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key)
			VALUES (1, 'direct', 'd:1:2')
			""").update();
	}
}
