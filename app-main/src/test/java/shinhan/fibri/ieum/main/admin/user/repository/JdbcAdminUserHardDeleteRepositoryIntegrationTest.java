package shinhan.fibri.ieum.main.admin.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcAdminUserHardDeleteRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_admin_user_hard_delete";

	private NamedParameterJdbcTemplate jdbc;
	private JdbcAdminUserHardDeleteRepository repository;

	@AfterAll
	static void cleanUpDatabase() {
		new NamedParameterJdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"))
			.update("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)", new MapSqlParameterSource());
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = new NamedParameterJdbcTemplate(CanonicalPostgresContainer.dataSource(DATABASE));
		repository = new JdbcAdminUserHardDeleteRepository(jdbc);
	}

	@Test
	void findForHardDeleteIncludesSoftDeletedUsers() {
		long userId = insertUser("deleted", "user");
		jdbc.update(
			"UPDATE users SET deleted_at = :deletedAt WHERE user_id = :userId",
			new MapSqlParameterSource("userId", userId)
				.addValue("deletedAt", OffsetDateTime.parse("2026-07-01T00:00:00Z"))
		);

		HardDeleteTarget target = repository.findForHardDelete(userId).orElseThrow();

		assertThat(target.userId()).isEqualTo(userId);
		assertThat(target.email()).isEqualTo("hard-delete-deleted@example.com");
		assertThat(target.role()).isEqualTo(UserRole.user);
	}

	@Test
	void hardDeleteRemovesOwnedRowsAndUploadedFilesAfterCollectingS3Keys() {
		long userId = insertUser("owner", "user");
		UUID profileFileId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		UUID chatFileId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		insertFile(profileFileId, userId, "final/" + userId + "/profile/" + profileFileId + "/original.jpg");
		insertFile(chatFileId, userId, "final/" + userId + "/chat/" + chatFileId + "/original.jpg");
		jdbc.update(
			"UPDATE users SET profile_file_id = :fileId WHERE user_id = :userId",
			new MapSqlParameterSource("userId", userId).addValue("fileId", profileFileId)
		);
		long pinId = insertPin(userId);
		long questionId = insertQuestion(userId, pinId);
		insertAnswer(questionId, userId);
		insertImageOnlyMessage(userId, chatFileId);

		List<String> s3Keys = repository.hardDelete(userId);

		assertThat(s3Keys).containsExactlyInAnyOrder(
			"final/" + userId + "/profile/" + profileFileId + "/original.jpg",
			"final/" + userId + "/chat/" + chatFileId + "/original.jpg"
		);
		assertThat(count("users", "user_id", userId)).isZero();
		assertThat(count("pins", "pin_id", pinId)).isZero();
		assertThat(count("questions", "question_id", questionId)).isZero();
		assertThat(count("files", "file_id", profileFileId)).isZero();
		assertThat(count("files", "file_id", chatFileId)).isZero();
	}

	@Test
	void hardDeleteWorksForSoftDeletedUsers() {
		long userId = insertUser("soft", "user");
		jdbc.update(
			"UPDATE users SET deleted_at = now() WHERE user_id = :userId",
			new MapSqlParameterSource("userId", userId)
		);

		List<String> s3Keys = repository.hardDelete(userId);

		assertThat(s3Keys).isEmpty();
		assertThat(count("users", "user_id", userId)).isZero();
	}

	@Test
	void isReferencedAsActorDetectsNonCascadingAdminSanctionReferences() {
		long actorId = insertUser("actor", "user");
		long otherUserId = insertUser("other", "user");

		jdbc.update(
			"""
				INSERT INTO user_sanctions (user_id, admin_id, sanction_type, reason)
				VALUES (:otherUserId, :actorId, 'permanent', 'manual decision')
				""",
			new MapSqlParameterSource("otherUserId", otherUserId).addValue("actorId", actorId)
		);

		assertThat(repository.isReferencedAsActor(actorId)).isTrue();
		assertThat(repository.isReferencedAsActor(otherUserId)).isFalse();
	}

	private long insertUser(String suffix, String role) {
		return jdbc.queryForObject(
			"""
				INSERT INTO users (email, password_hash, nickname, email_verified, role)
				VALUES (:email, 'hash', :nickname, true, CAST(:role AS user_role))
				RETURNING user_id
				""",
			new MapSqlParameterSource()
				.addValue("email", "hard-delete-" + suffix + "@example.com")
				.addValue("nickname", "hard-delete-" + suffix)
				.addValue("role", role),
			Long.class
		);
	}

	private void insertFile(UUID fileId, long userId, String s3Key) {
		jdbc.update(
			"""
				INSERT INTO files (file_id, uploader_id, s3_key, content_type, size_bytes, uploaded_at)
				VALUES (:fileId, :userId, :s3Key, 'image/jpeg', 1024, now())
				""",
			new MapSqlParameterSource()
				.addValue("fileId", fileId)
				.addValue("userId", userId)
				.addValue("s3Key", s3Key)
		);
	}

	private long insertPin(long userId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO pins (author_id, pin_type, location, address)
				VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
				RETURNING pin_id
				""",
			new MapSqlParameterSource("userId", userId),
			Long.class
		);
	}

	private long insertQuestion(long userId, long pinId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO questions (pin_id, author_id, title, content)
				VALUES (:pinId, :userId, 'question', 'content')
				RETURNING question_id
				""",
			new MapSqlParameterSource("pinId", pinId).addValue("userId", userId),
			Long.class
		);
	}

	private void insertAnswer(long questionId, long userId) {
		jdbc.update(
			"""
				INSERT INTO answers (question_id, author_id, is_ai, content)
				VALUES (:questionId, :userId, false, 'answer')
				""",
			new MapSqlParameterSource("questionId", questionId).addValue("userId", userId)
		);
	}

	private void insertImageOnlyMessage(long userId, UUID fileId) {
		long roomId = jdbc.queryForObject(
			"""
				INSERT INTO chat_rooms (room_type, room_key)
				VALUES ('direct', :roomKey)
				RETURNING room_id
				""",
			new MapSqlParameterSource("roomKey", "d:" + userId + ":999"),
			Long.class
		);
		jdbc.update(
			"""
				INSERT INTO messages (room_id, sender_id, image_file_id)
				VALUES (:roomId, :userId, :fileId)
				""",
			new MapSqlParameterSource("roomId", roomId)
				.addValue("userId", userId)
				.addValue("fileId", fileId)
		);
	}

	private long count(String table, String column, Object value) {
		return jdbc.queryForObject(
			"SELECT count(*) FROM " + table + " WHERE " + column + " = :value",
			new MapSqlParameterSource("value", value),
			Long.class
		);
	}
}
