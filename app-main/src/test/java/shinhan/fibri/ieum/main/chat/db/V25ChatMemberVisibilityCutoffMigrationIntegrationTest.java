package shinhan.fibri.ieum.main.chat.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class V25ChatMemberVisibilityCutoffMigrationIntegrationTest {

	private static final String DATABASE = "ieum_v25_chat_member_visibility";

	private JdbcClient jdbc;

	@BeforeEach
	void recreateLegacyDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "test-baselines/schema-v12.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedLegacyChatRooms();
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void v25BackfillsCutoffWithoutReaddingLegacyLeftOneToOneMembers() {
		applyMigration();

		assertThat(memberState(10, 1)).isEqualTo(new MemberState(false, 105));
		assertThat(memberState(20, 2)).isEqualTo(new MemberState(false, 209));
		assertThat(memberState(30, 3)).isEqualTo(new MemberState(true, 0));
		assertThat(memberState(40, 4)).isEqualTo(new MemberState(false, 0));
	}

	@Test
	void v25AddsTheVisibilityColumnDefaultAndNonnegativeCheck() {
		applyMigration();

		ColumnContract column = jdbc.sql("""
			SELECT data_type, is_nullable, column_default
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'chat_members'
			  AND column_name = 'visible_after_message_id'
			""")
			.query((resultSet, rowNumber) -> new ColumnContract(
				resultSet.getString("data_type"),
				resultSet.getString("is_nullable"),
				resultSet.getString("column_default")
			))
			.single();

		assertThat(column.dataType()).isEqualTo("bigint");
		assertThat(column.nullable()).isEqualTo("NO");
		assertThat(column.defaultValue()).contains("0");
		assertThat(jdbc.sql("""
			SELECT convalidated
			FROM pg_constraint
			WHERE conrelid = 'chat_members'::regclass
			  AND conname = 'ck_chat_members_visible_after_message_id'
			""").query(Boolean.class).single()).isTrue();

		jdbc.sql("INSERT INTO chat_members (room_id, user_id) VALUES (30, 5)").update();
		assertThat(memberState(30, 5)).isEqualTo(new MemberState(true, 0));
		assertThatThrownBy(() -> jdbc.sql("""
			UPDATE chat_members
			SET visible_after_message_id = -1
			WHERE room_id = 30 AND user_id = 5
			""").update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void applyMigration() {
		SqlScriptRunner.run(DATABASE, "migrations/v25_chat_member_visibility_cutoff.sql");
	}

	private MemberState memberState(long roomId, long userId) {
		return jdbc.sql("""
			SELECT left_at IS NULL AS active, visible_after_message_id
			FROM chat_members
			WHERE room_id = :roomId AND user_id = :userId
			""")
			.param("roomId", roomId)
			.param("userId", userId)
			.query((resultSet, rowNumber) -> new MemberState(
				resultSet.getBoolean("active"),
				resultSet.getLong("visible_after_message_id")
			))
			.single();
	}

	private void seedLegacyChatRooms() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, nickname)
			VALUES
			  (1, 'member-1@example.com', 'member-1'),
			  (2, 'member-2@example.com', 'member-2'),
			  (3, 'member-3@example.com', 'member-3'),
			  (4, 'member-4@example.com', 'member-4'),
			  (5, 'member-5@example.com', 'member-5')
			""").update();
		jdbc.sql("""
			INSERT INTO pins (pin_id, author_id, pin_type, location, address)
			VALUES
			  (100, 1, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울'),
			  (101, 4, 'meeting', ST_SetSRID(ST_MakePoint(127.1, 37.6), 4326)::geography, '서울')
			""").update();
		jdbc.sql("""
			INSERT INTO questions (question_id, pin_id, author_id, title, content)
			VALUES (200, 100, 1, '기존 질문', '기존 질문 내용')
			""").update();
		jdbc.sql("""
			INSERT INTO meetings (meeting_id, pin_id, host_id, title, meeting_at)
			VALUES (300, 101, 4, '기존 모임', '2026-08-01T10:00:00+09:00')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, meeting_id, question_id, room_key)
			VALUES
			  (10, 'direct', NULL, NULL, 'd:1:2'),
			  (20, 'question', NULL, 200, 'q:200:2:3'),
			  (30, 'direct', NULL, NULL, 'd:3:4'),
			  (40, 'group', 300, NULL, NULL)
			""").update();
		jdbc.sql("""
			INSERT INTO chat_members (room_id, user_id, left_at)
			VALUES
			  (10, 1, '2026-07-01T10:00:00+09:00'),
			  (10, 2, NULL),
			  (20, 2, '2026-07-02T10:00:00+09:00'),
			  (20, 3, NULL),
			  (30, 3, NULL),
			  (30, 4, NULL),
			  (40, 4, '2026-07-03T10:00:00+09:00'),
			  (40, 5, NULL)
			""").update();
		jdbc.sql("""
			INSERT INTO messages (message_id, room_id, sender_id, content)
			VALUES
			  (101, 10, 2, 'direct-1'),
			  (105, 10, 2, 'direct-2'),
			  (201, 20, 3, 'question-1'),
			  (209, 20, 3, 'question-2'),
			  (301, 30, 4, 'active-direct'),
			  (401, 40, 5, 'group')
			""").update();
	}

	private record MemberState(boolean active, long visibilityCutoff) {
	}

	private record ColumnContract(String dataType, String nullable, String defaultValue) {
	}
}
