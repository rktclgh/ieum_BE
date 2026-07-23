package shinhan.fibri.ieum.main.admin.content.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.main.admin.content.domain.AdminContentType;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class JdbcAdminContentHardDeleteRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_admin_content_hard_delete";

	private DataSource dataSource;
	private NamedParameterJdbcTemplate jdbc;
	private JdbcTemplate plainJdbc;
	private JdbcAdminContentHardDeleteRepository repository;
	private TransactionTemplate transactionTemplate;

	@AfterAll
	static void cleanUpDatabase() {
		new NamedParameterJdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"))
			.update("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)", new MapSqlParameterSource());
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = new NamedParameterJdbcTemplate(dataSource);
		plainJdbc = new JdbcTemplate(dataSource);
		repository = new JdbcAdminContentHardDeleteRepository(jdbc);
		transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Test
	void previewIncludesSoftDeletedQuestion() {
		long userId = insertUser("question-preview");
		long questionId = insertQuestion(userId, "preview", OffsetDateTime.parse("2026-07-01T00:00:00Z"));

		AdminContentHardDeleteTarget target = repository.preview(AdminContentType.QUESTION, questionId).orElseThrow();

		assertThat(target.contentType()).isEqualTo(AdminContentType.QUESTION);
		assertThat(target.contentId()).isEqualTo(questionId);
		assertThat(target.title()).isEqualTo("preview");
		assertThat(target.authorNickname()).isEqualTo("question-preview");
		assertThat(target.authorId()).isEqualTo(userId);
		assertThat(target.deletedAt()).isEqualTo(OffsetDateTime.parse("2026-07-01T00:00:00Z"));
	}

	@Test
	void hardDeleteQuestionRemovesCascadedRowsNotificationsAndOnlyOrphanedFiles() {
		long ownerId = insertUser("question-owner");
		long otherUserId = insertUser("question-other");
		UUID questionFile = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID answerFile = UUID.fromString("22222222-2222-2222-2222-222222222222");
		UUID sharedFile = UUID.fromString("33333333-3333-3333-3333-333333333333");
		insertFile(questionFile, ownerId, "final/question/" + questionFile + "/original.jpg");
		insertFile(answerFile, otherUserId, "final/answer/" + answerFile + "/original.jpg");
		insertFile(sharedFile, ownerId, "final/shared/" + sharedFile + "/original.jpg");
		long questionId = insertQuestion(ownerId, "question", null);
		long answerId = insertAnswer(questionId, otherUserId);
		linkQuestionImage(questionId, questionFile);
		linkAnswerImage(answerId, answerFile);
		linkQuestionImage(questionId, sharedFile);
		long otherQuestionId = insertQuestion(otherUserId, "other", null);
		linkQuestionImage(otherQuestionId, sharedFile);
		insertQuestionChatImageMessage(questionId, ownerId, answerFile);
		insertAiQuestionTask(questionId);
		insertKnowledgeSource(questionId, answerId);
		insertQuestionNotification(ownerId, questionId);
		long reportId = insertAnswerReport(ownerId, answerId);

		AdminContentHardDeleteTarget target = repository.findForHardDelete(AdminContentType.QUESTION, questionId).orElseThrow();
		AdminContentHardDeleteResult result = repository.hardDelete(AdminContentType.QUESTION, target);

		assertThat(result.s3Keys()).containsExactlyInAnyOrder(
			"final/question/" + questionFile + "/original.jpg",
			"final/answer/" + answerFile + "/original.jpg"
		);
		assertThat(count("questions", "question_id", questionId)).isZero();
		assertThat(count("answers", "answer_id", answerId)).isZero();
		assertThat(count("ai_question_tasks", "question_id", questionId)).isZero();
		assertThat(count("knowledge_sources", "question_id", questionId)).isZero();
		assertThat(notificationCount("question", questionId)).isZero();
		assertThat(reportAnswerId(reportId)).isNull();
		assertThat(count("reports", "report_id", reportId)).isOne();
		assertThat(count("files", "file_id", questionFile)).isZero();
		assertThat(count("files", "file_id", answerFile)).isZero();
		assertThat(count("files", "file_id", sharedFile)).isOne();
		assertThat(count("questions", "question_id", otherQuestionId)).isOne();
	}

	@Test
	void hardDeleteQuestionLocksChatRoomBeforeFileSnapshotSoConcurrentMessageInsertCannotSlipIn() throws Exception {
		long ownerId = insertUser("question-lock-owner");
		UUID chatFile = UUID.fromString("44444444-4444-4444-4444-444444444444");
		insertFile(chatFile, ownerId, "final/chat/" + chatFile + "/original.jpg");
		long questionId = insertQuestion(ownerId, "question-lock", null);
		long roomId = insertQuestionRoom(questionId);
		insertImageOnlyMessageInRoom(roomId, ownerId, chatFile);
		AtomicInteger hardDeletePid = new AtomicInteger();

		try (Connection filesBarrier = dataSource.getConnection();
			 ExecutorService executor = Executors.newFixedThreadPool(2)) {
			lockFilesTable(filesBarrier);
			Future<AdminContentHardDeleteResult> hardDelete = executor.submit(() -> transactionTemplate.execute(status -> {
				hardDeletePid.set(plainJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
				AdminContentHardDeleteTarget target = repository.findForHardDelete(AdminContentType.QUESTION, questionId)
					.orElseThrow();
				return repository.hardDelete(AdminContentType.QUESTION, target);
			}));

			await("hard delete backend pid", () -> hardDeletePid.get() > 0);
			await("hard delete chat room lock", () -> hasChatRoomRowShareLock(hardDeletePid.get()));

			Future<Integer> concurrentMessageInsert = executor.submit(() -> plainJdbc.update(
				"INSERT INTO messages (room_id, sender_id, content) VALUES (?, ?, 'late message')",
				roomId,
				ownerId
			));

			assertThatThrownBy(() -> concurrentMessageInsert.get(500, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			filesBarrier.commit();

			assertThat(hardDelete.get(10, TimeUnit.SECONDS).s3Keys())
				.containsExactly("final/chat/" + chatFile + "/original.jpg");
			assertThatThrownBy(() -> concurrentMessageInsert.get(10, TimeUnit.SECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(DataIntegrityViolationException.class);
			assertThat(count("chat_rooms", "room_id", roomId)).isZero();
			assertThat(count("questions", "question_id", questionId)).isZero();
		}
	}

	@Test
	void hardDeleteMeetingRemovesCascadedRowsNotificationsAndOnlyOrphanedFiles() {
		long hostId = insertUser("meeting-host");
		long participantId = insertUser("meeting-participant");
		UUID meetingImageFile = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		UUID chatFile = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		UUID sharedFile = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
		insertFile(meetingImageFile, hostId, "final/meeting/" + meetingImageFile + "/original.jpg");
		insertFile(chatFile, participantId, "final/chat/" + chatFile + "/original.jpg");
		insertFile(sharedFile, hostId, "final/shared/" + sharedFile + "/original.jpg");
		long meetingId = insertMeeting(hostId, meetingImageFile, sharedFile, OffsetDateTime.parse("2026-07-01T00:00:00Z"));
		long scheduleId = insertMeetingSchedule(meetingId, hostId);
		insertMeetingParticipant(meetingId, participantId);
		insertMeetingRecurrenceRule(meetingId);
		long roomId = insertGroupRoom(meetingId);
		insertImageOnlyMessageInRoom(roomId, participantId, chatFile);
		long otherMeetingId = insertMeeting(participantId, sharedFile, null, null);
		insertMeetingNotification(hostId, meetingId);
		long reportId = insertScheduleReport(hostId, participantId, scheduleId);

		AdminContentHardDeleteTarget target = repository.findForHardDelete(AdminContentType.MEETING, meetingId).orElseThrow();
		AdminContentHardDeleteResult result = repository.hardDelete(AdminContentType.MEETING, target);

		assertThat(result.s3Keys()).containsExactlyInAnyOrder(
			"final/meeting/" + meetingImageFile + "/original.jpg",
			"final/chat/" + chatFile + "/original.jpg"
		);
		assertThat(count("meetings", "meeting_id", meetingId)).isZero();
		assertThat(count("meeting_schedules", "schedule_id", scheduleId)).isZero();
		assertThat(count("meeting_participants", "meeting_id", meetingId)).isZero();
		assertThat(count("meeting_recurrence_rules", "meeting_id", meetingId)).isZero();
		assertThat(count("chat_rooms", "room_id", roomId)).isZero();
		assertThat(notificationCount("meeting", meetingId)).isZero();
		assertThat(reportScheduleId(reportId)).isNull();
		assertThat(count("reports", "report_id", reportId)).isOne();
		assertThat(count("files", "file_id", meetingImageFile)).isZero();
		assertThat(count("files", "file_id", chatFile)).isZero();
		assertThat(count("files", "file_id", sharedFile)).isOne();
		assertThat(count("meetings", "meeting_id", otherMeetingId)).isOne();
	}

	private long insertUser(String nickname) {
		return jdbc.queryForObject(
			"""
				INSERT INTO users (email, password_hash, nickname, email_verified)
				VALUES (:email, 'hash', :nickname, true)
				RETURNING user_id
				""",
			new MapSqlParameterSource()
				.addValue("email", nickname + "@example.com")
				.addValue("nickname", nickname),
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

	private long insertMeeting(long userId, UUID imageFileId, UUID thumbnailFileId, OffsetDateTime deletedAt) {
		long pinId = insertPin(userId, "meeting");
		return jdbc.queryForObject(
			"""
				INSERT INTO meetings (pin_id, host_id, title, content, meeting_at, image_file_id, thumbnail_file_id, deleted_at)
				VALUES (:pinId, :userId, 'meeting', 'content', now(), :imageFileId, :thumbnailFileId, :deletedAt)
				RETURNING meeting_id
				""",
			new MapSqlParameterSource()
				.addValue("pinId", pinId)
				.addValue("userId", userId)
				.addValue("imageFileId", imageFileId)
				.addValue("thumbnailFileId", thumbnailFileId)
				.addValue("deletedAt", deletedAt),
			Long.class
		);
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

	private void insertQuestionChatImageMessage(long questionId, long userId, UUID fileId) {
		long roomId = insertQuestionRoom(questionId);
		insertImageOnlyMessageInRoom(roomId, userId, fileId);
	}

	private long insertQuestionRoom(long questionId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO chat_rooms (room_type, question_id, room_key)
				VALUES ('question', :questionId, :roomKey)
				RETURNING room_id
				""",
			new MapSqlParameterSource("questionId", questionId).addValue("roomKey", "q:" + questionId + ":1:2"),
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
			new MapSqlParameterSource("roomId", roomId).addValue("userId", userId).addValue("fileId", fileId)
		);
	}

	private void insertAiQuestionTask(long questionId) {
		jdbc.update("INSERT INTO ai_question_tasks (question_id) VALUES (:questionId)", new MapSqlParameterSource("questionId", questionId));
	}

	private void insertKnowledgeSource(long questionId, long answerId) {
		jdbc.update(
			"""
				INSERT INTO knowledge_sources (source_type, question_id, answer_id, content_hash, display_name, status)
				VALUES ('accepted_human_answer', :questionId, :answerId,
				        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
				        'answer', 'ready')
				""",
			new MapSqlParameterSource("questionId", questionId).addValue("answerId", answerId)
		);
	}

	private long insertMeetingSchedule(long meetingId, long userId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO meeting_schedules (
					meeting_id, created_by, starts_on, starts_at, visible_until, sequence_no
				)
				VALUES (:meetingId, :userId, CURRENT_DATE, now(), now() + interval '1 day', 1)
				RETURNING schedule_id
				""",
			new MapSqlParameterSource("meetingId", meetingId).addValue("userId", userId),
			Long.class
		);
	}

	private void insertMeetingParticipant(long meetingId, long userId) {
		jdbc.update(
			"INSERT INTO meeting_participants (meeting_id, user_id) VALUES (:meetingId, :userId)",
			new MapSqlParameterSource("meetingId", meetingId).addValue("userId", userId)
		);
	}

	private void insertMeetingRecurrenceRule(long meetingId) {
		jdbc.update(
			"""
				INSERT INTO meeting_recurrence_rules (meeting_id, frequency, starts_on)
				VALUES (:meetingId, 'weekly', CURRENT_DATE)
				""",
			new MapSqlParameterSource("meetingId", meetingId)
		);
	}

	private void insertQuestionNotification(long userId, long questionId) {
		jdbc.update(
			"""
				INSERT INTO notifications (user_id, type, title, ref_id)
				VALUES (:userId, 'question', 'question notification', :refId)
				""",
			new MapSqlParameterSource("userId", userId).addValue("refId", questionId)
		);
	}

	private void insertMeetingNotification(long userId, long meetingId) {
		jdbc.update(
			"""
				INSERT INTO notifications (user_id, type, title, ref_id)
				VALUES (:userId, 'meeting', 'meeting notification', :refId)
				""",
			new MapSqlParameterSource("userId", userId).addValue("refId", meetingId)
		);
	}

	private long insertAnswerReport(long reporterId, long answerId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO reports (reporter_id, target_type, answer_id, reason, context_hash, ai_review_state)
				VALUES (:reporterId, 'answer', :answerId, 'etc',
				        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
				        'cancelled')
				RETURNING report_id
				""",
			new MapSqlParameterSource("reporterId", reporterId).addValue("answerId", answerId),
			Long.class
		);
	}

	private long insertScheduleReport(long reporterId, long reportedUserId, long scheduleId) {
		return jdbc.queryForObject(
			"""
				INSERT INTO reports (
					reporter_id, target_type, schedule_id, reported_user_id, reason, context_hash, ai_review_state
				)
				VALUES (:reporterId, 'schedule', :scheduleId, :reportedUserId, 'etc',
				        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
				        'cancelled')
				RETURNING report_id
				""",
			new MapSqlParameterSource("reporterId", reporterId)
				.addValue("reportedUserId", reportedUserId)
				.addValue("scheduleId", scheduleId),
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

	private long notificationCount(String type, long refId) {
		return jdbc.queryForObject(
			"SELECT count(*) FROM notifications WHERE type = CAST(:type AS notification_type) AND ref_id = :refId",
			new MapSqlParameterSource("type", type).addValue("refId", refId),
			Long.class
		);
	}

	private Long reportAnswerId(long reportId) {
		return jdbc.queryForObject(
			"SELECT answer_id FROM reports WHERE report_id = :reportId",
			new MapSqlParameterSource("reportId", reportId),
			Long.class
		);
	}

	private Long reportScheduleId(long reportId) {
		return jdbc.queryForObject(
			"SELECT schedule_id FROM reports WHERE report_id = :reportId",
			new MapSqlParameterSource("reportId", reportId),
			Long.class
		);
	}

	private void lockFilesTable(Connection connection) throws Exception {
		connection.setAutoCommit(false);
		try (Statement statement = connection.createStatement()) {
			statement.execute("LOCK TABLE files IN ACCESS EXCLUSIVE MODE");
		}
	}

	private boolean hasChatRoomRowShareLock(int pid) {
		Boolean locked = jdbc.queryForObject(
			"""
				SELECT EXISTS (
					SELECT 1
					  FROM pg_locks lock
					 WHERE lock.pid = :pid
					   AND lock.locktype = 'relation'
					   AND lock.relation = 'public.chat_rooms'::regclass
					   AND lock.mode = 'RowShareLock'
					   AND lock.granted
				)
				""",
			new MapSqlParameterSource("pid", pid),
			Boolean.class
		);
		return Boolean.TRUE.equals(locked);
	}

	private void await(String conditionName, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("Timed out waiting for " + conditionName);
	}
}
