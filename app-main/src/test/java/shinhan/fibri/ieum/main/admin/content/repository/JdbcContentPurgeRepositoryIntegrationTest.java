package shinhan.fibri.ieum.main.admin.content.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcContentPurgeRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_admin_content_purge";

	private NamedParameterJdbcTemplate jdbc;
	private JdbcContentPurgeRepository repository;

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
		repository = new JdbcContentPurgeRepository(jdbc);
	}

	@Test
	void purgesOnlyQuestionRowsOlderThanCutoffAndDeletesFilesLast() {
		long userId = insertUser("owner");
		UUID questionFile = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID answerFile = UUID.fromString("22222222-2222-2222-2222-222222222222");
		UUID messageFile = UUID.fromString("44444444-4444-4444-4444-444444444444");
		long oldQuestionId = insertQuestion(userId, "old", OffsetDateTime.parse("2026-03-01T00:00:00Z"));
		long oldAnswerId = insertAnswer(oldQuestionId, userId);
		insertFile(questionFile, userId, "final/42/question/" + questionFile + "/original.jpg");
		insertFile(answerFile, userId, "final/42/answer/" + answerFile + "/original.jpg");
		insertFile(messageFile, userId, "final/42/chat/" + messageFile + "/original.jpg");
		linkQuestionImage(oldQuestionId, questionFile);
		linkAnswerImage(oldAnswerId, answerFile);
		insertQuestionChatImageMessage(oldQuestionId, userId, messageFile);
		insertAiQuestionTask(oldQuestionId);
		insertKnowledgeSource(oldQuestionId, oldAnswerId);

		long recentQuestionId = insertQuestion(userId, "recent", OffsetDateTime.parse("2026-06-01T00:00:00Z"));
		UUID recentFile = UUID.fromString("33333333-3333-3333-3333-333333333333");
		insertFile(recentFile, userId, "final/42/question/" + recentFile + "/original.jpg");
		linkQuestionImage(recentQuestionId, recentFile);

		long meetingPinId = insertMeeting(userId);

		ContentPurgeChunk result = repository.purgeChunk(OffsetDateTime.parse("2026-05-01T00:00:00Z"), 500);

		assertThat(result.purgedCount()).isEqualTo(1);
		assertThat(result.s3Keys()).containsExactlyInAnyOrder(
			"final/42/question/" + questionFile + "/original.jpg",
			"final/42/answer/" + answerFile + "/original.jpg",
			"final/42/chat/" + messageFile + "/original.jpg"
		);
		assertThat(count("questions", "question_id", oldQuestionId)).isZero();
		assertThat(count("answers", "answer_id", oldAnswerId)).isZero();
		assertThat(count("ai_question_tasks", "question_id", oldQuestionId)).isZero();
		assertThat(count("knowledge_sources", "question_id", oldQuestionId)).isZero();
		assertThat(count("files", "file_id", questionFile)).isZero();
		assertThat(count("files", "file_id", answerFile)).isZero();
		assertThat(count("files", "file_id", messageFile)).isZero();
		assertThat(count("questions", "question_id", recentQuestionId)).isEqualTo(1);
		assertThat(count("files", "file_id", recentFile)).isEqualTo(1);
		assertThat(count("meetings", "pin_id", meetingPinId)).isEqualTo(1);
		assertThat(count("pins", "pin_id", meetingPinId)).isEqualTo(1);
	}

	@Test
	void emptyWhenNoDeletedQuestionsArePastCutoff() {
		long userId = insertUser("none");
		insertQuestion(userId, "recent", OffsetDateTime.parse("2026-06-01T00:00:00Z"));

		ContentPurgeChunk result = repository.purgeChunk(OffsetDateTime.parse("2026-05-01T00:00:00Z"), 500);

		assertThat(result.isEmpty()).isTrue();
	}

	private long insertUser(String suffix) {
		return jdbc.queryForObject(
			"""
				INSERT INTO users (email, password_hash, nickname, email_verified)
				VALUES (:email, 'hash', :nickname, true)
				RETURNING user_id
				""",
			new MapSqlParameterSource()
				.addValue("email", "content-purge-" + suffix + "@example.com")
				.addValue("nickname", "purge-" + suffix),
			Long.class
		);
	}

	private long insertQuestion(long userId, String title, OffsetDateTime deletedAt) {
		long pinId = insertPin(userId, "question");
		return jdbc.queryForObject(
			"""
				INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
				VALUES (:pinId, :userId, :title, 'content', :deletedAt)
				RETURNING question_id
				""",
			new MapSqlParameterSource()
				.addValue("pinId", pinId)
				.addValue("userId", userId)
				.addValue("title", title)
				.addValue("deletedAt", deletedAt),
			Long.class
		);
	}

	private long insertAnswer(long questionId, long userId) {
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

	private void linkQuestionImage(long questionId, UUID fileId) {
		jdbc.update(
			"INSERT INTO question_images (question_id, file_id) VALUES (:questionId, :fileId)",
			new MapSqlParameterSource("questionId", questionId).addValue("fileId", fileId)
		);
	}

	private void linkAnswerImage(long answerId, UUID fileId) {
		jdbc.update(
			"INSERT INTO answer_images (answer_id, file_id) VALUES (:answerId, :fileId)",
			new MapSqlParameterSource("answerId", answerId).addValue("fileId", fileId)
		);
	}

	private void insertAiQuestionTask(long questionId) {
		jdbc.update(
			"INSERT INTO ai_question_tasks (question_id) VALUES (:questionId)",
			new MapSqlParameterSource("questionId", questionId)
		);
	}

	private void insertKnowledgeSource(long questionId, long answerId) {
		jdbc.update(
			"""
				INSERT INTO knowledge_sources (
					source_type, question_id, answer_id, content_hash, display_name, status
				)
				VALUES (
					'accepted_human_answer', :questionId, :answerId,
					'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
					'answer', 'ready'
				)
				""",
			new MapSqlParameterSource("questionId", questionId).addValue("answerId", answerId)
		);
	}

	private void insertQuestionChatImageMessage(long questionId, long userId, UUID fileId) {
		long roomId = jdbc.queryForObject(
			"""
				INSERT INTO chat_rooms (room_type, question_id, room_key)
				VALUES ('question', :questionId, :roomKey)
				RETURNING room_id
				""",
			new MapSqlParameterSource("questionId", questionId)
				.addValue("roomKey", "q:" + questionId + ":1:2"),
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

	private long insertMeeting(long userId) {
		long pinId = insertPin(userId, "meeting");
		jdbc.update(
			"""
				INSERT INTO meetings (pin_id, host_id, title, content, meeting_at, deleted_at)
				VALUES (:pinId, :userId, 'meeting', 'content', now(), :deletedAt)
				""",
			new MapSqlParameterSource()
				.addValue("pinId", pinId)
				.addValue("userId", userId)
				.addValue("deletedAt", OffsetDateTime.parse("2026-03-01T00:00:00Z"))
		);
		return pinId;
	}

	private long insertPin(long userId, String pinType) {
		return jdbc.queryForObject(
			"""
				INSERT INTO pins (author_id, pin_type, location, address)
				VALUES (:userId, CAST(:pinType AS pin_type), ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
				RETURNING pin_id
				""",
			new MapSqlParameterSource("userId", userId).addValue("pinType", pinType),
			Long.class
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
