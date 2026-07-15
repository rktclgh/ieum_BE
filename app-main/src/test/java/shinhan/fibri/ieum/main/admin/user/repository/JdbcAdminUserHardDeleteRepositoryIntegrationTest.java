package shinhan.fibri.ieum.main.admin.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@ExtendWith(OutputCaptureExtension.class)
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
	void hardDeleteCollectsFilesReferencedByRowsCascadedFromTargetOwnedContent() {
		long targetUserId = insertUser("cascade-owner", "user");
		long otherUserId = insertUser("cascade-other", "user");
		UUID questionImageFileId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
		UUID answerImageFileId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
		UUID meetingImageFileId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
		UUID meetingMessageFileId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
		insertFile(questionImageFileId, otherUserId, "final/" + otherUserId + "/question/" + questionImageFileId + "/original.jpg");
		insertFile(answerImageFileId, otherUserId, "final/" + otherUserId + "/answer/" + answerImageFileId + "/original.jpg");
		insertFile(meetingImageFileId, otherUserId, "final/" + otherUserId + "/meeting/" + meetingImageFileId + "/original.jpg");
		insertFile(meetingMessageFileId, otherUserId, "final/" + otherUserId + "/chat/" + meetingMessageFileId + "/original.jpg");
		long questionPinId = insertPin(targetUserId);
		long questionId = insertQuestion(targetUserId, questionPinId);
		insertQuestionImage(questionId, questionImageFileId);
		long answerId = insertAnswerReturningId(questionId, otherUserId);
		insertAnswerImage(answerId, answerImageFileId);
		long meetingPinId = insertPin(targetUserId);
		long meetingId = insertMeeting(targetUserId, meetingPinId, meetingImageFileId);
		long roomId = insertGroupRoom(meetingId);
		insertImageOnlyMessageInRoom(roomId, otherUserId, meetingMessageFileId);

		List<String> s3Keys = repository.hardDelete(targetUserId);

		assertThat(s3Keys).contains(
			"final/" + otherUserId + "/question/" + questionImageFileId + "/original.jpg",
			"final/" + otherUserId + "/answer/" + answerImageFileId + "/original.jpg",
			"final/" + otherUserId + "/meeting/" + meetingImageFileId + "/original.jpg",
			"final/" + otherUserId + "/chat/" + meetingMessageFileId + "/original.jpg"
		);
		assertThat(count("files", "file_id", questionImageFileId)).isZero();
		assertThat(count("files", "file_id", answerImageFileId)).isZero();
		assertThat(count("files", "file_id", meetingImageFileId)).isZero();
		assertThat(count("files", "file_id", meetingMessageFileId)).isZero();
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
	void hardDeleteLogsCollectedS3KeyCountWithoutFullKeyPayload(CapturedOutput output) {
		long userId = insertUser("log-count", "user");
		UUID fileId = UUID.fromString("99999999-9999-9999-9999-999999999999");
		String s3Key = "final/" + userId + "/profile/" + fileId + "/original.jpg";
		insertFile(fileId, userId, s3Key);

		repository.hardDelete(userId);

		assertThat(output)
			.contains("Admin user hard delete collected S3 keys before DB delete")
			.contains("s3KeyCount=1")
			.doesNotContain("s3Keys=")
			.doesNotContain(s3Key);
	}

	@Test
	void hardDeleteResetsQuestionResolvedByDeletedUsersAcceptedAnswer() {
		long questionOwnerId = insertUser("question-owner", "user");
		long answerAuthorId = insertUser("accepted-answer-author", "user");
		long pinId = insertPin(questionOwnerId);
		long questionId = insertQuestion(questionOwnerId, pinId);
		insertAcceptedAnswer(questionId, answerAuthorId);
		jdbc.update(
			"UPDATE questions SET is_resolved = true WHERE question_id = :questionId",
			new MapSqlParameterSource("questionId", questionId)
		);

		repository.hardDelete(answerAuthorId);

		Boolean resolved = jdbc.queryForObject(
			"SELECT is_resolved FROM questions WHERE question_id = :questionId",
			new MapSqlParameterSource("questionId", questionId),
			Boolean.class
		);
		assertThat(resolved).isFalse();
		assertThat(count("answers", "author_id", answerAuthorId)).isZero();
		assertThat(count("questions", "question_id", questionId)).isOne();
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

	private long insertAnswerReturningId(long questionId, long userId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO answers (question_id, author_id, is_ai, content)
				VALUES (:questionId, :userId, false, 'answer')
				RETURNING answer_id
				""",
			new MapSqlParameterSource("questionId", questionId).addValue("userId", userId),
			Long.class
		);
	}

	private void insertAcceptedAnswer(long questionId, long userId) {
		jdbc.update(
			"""
				INSERT INTO answers (question_id, author_id, is_ai, content, is_accepted)
				VALUES (:questionId, :userId, false, 'accepted answer', true)
				""",
			new MapSqlParameterSource("questionId", questionId).addValue("userId", userId)
		);
	}

	private void insertQuestionImage(long questionId, UUID fileId) {
		jdbc.update(
			"""
				INSERT INTO question_images (question_id, file_id)
				VALUES (:questionId, :fileId)
				""",
			new MapSqlParameterSource("questionId", questionId).addValue("fileId", fileId)
		);
	}

	private void insertAnswerImage(long answerId, UUID fileId) {
		jdbc.update(
			"""
				INSERT INTO answer_images (answer_id, file_id)
				VALUES (:answerId, :fileId)
				""",
			new MapSqlParameterSource("answerId", answerId).addValue("fileId", fileId)
		);
	}

	private long insertMeeting(long userId, long pinId, UUID imageFileId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO meetings (pin_id, host_id, title, content, meeting_at, image_file_id)
				VALUES (:pinId, :userId, 'meeting', 'content', now() + interval '1 day', :imageFileId)
				RETURNING meeting_id
				""",
			new MapSqlParameterSource("pinId", pinId)
				.addValue("userId", userId)
				.addValue("imageFileId", imageFileId),
			Long.class
		);
	}

	private long insertGroupRoom(long meetingId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO chat_rooms (room_type, meeting_id)
				VALUES ('group', :meetingId)
				RETURNING room_id
				""",
			new MapSqlParameterSource("meetingId", meetingId),
			Long.class
		);
	}

	private void insertImageOnlyMessageInRoom(long roomId, long userId, UUID fileId) {
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
