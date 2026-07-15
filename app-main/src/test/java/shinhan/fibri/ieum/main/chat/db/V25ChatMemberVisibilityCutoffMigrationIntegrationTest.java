package shinhan.fibri.ieum.main.chat.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
	void recreateDatabaseWithLegacyMember() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "test-baselines/schema-v12.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		seedLegacyMember();
	}

	@Test
	void v25BackfillsAValidatedVisibilityWatermarkAndAddsTheRoomMessageIndex() {
		SqlScriptRunner.run(DATABASE, "migrations/v25_chat_member_visibility_cutoff.sql");

		String defaultValue = jdbc.sql("""
			SELECT column_default
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'chat_members'
			  AND column_name = 'visible_after_message_id'
			""").query(String.class).single();
		String nullable = jdbc.sql("""
			SELECT is_nullable
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'chat_members'
			  AND column_name = 'visible_after_message_id'
			""").query(String.class).single();
		List<String> indexes = jdbc.sql("""
			SELECT indexname
			FROM pg_indexes
			WHERE schemaname = 'public' AND tablename = 'messages'
			""").query(String.class).list();

		assertThat(defaultValue).contains("0");
		assertThat(nullable).isEqualTo("NO");
		assertThat(jdbc.sql("SELECT visible_after_message_id FROM chat_members")
			.query(Long.class).single()).isZero();
		assertThat(indexes).contains("idx_messages_room_message_id");
		assertThatThrownBy(() -> jdbc.sql("""
			UPDATE chat_members
			SET visible_after_message_id = -1
			""").update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void seedLegacyMember() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, nickname)
			VALUES (1, 'legacy-member@example.com', 'legacy-member')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_id, room_type, room_key)
			VALUES (10, 'direct', 'd:1:2')
			""").update();
		jdbc.sql("""
			INSERT INTO chat_members (room_id, user_id)
			VALUES (10, 1)
			""").update();
	}
}
