package shinhan.fibri.ieum.main.admin.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportDetailRow;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportSanctionRow;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;

class AdminReportDetailRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_admin_report_detail";
	private static AdminReportRepository repository;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void setUpDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		var dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = new JdbcTemplate(dataSource);
		repository = new AdminReportRepository(new NamedParameterJdbcTemplate(dataSource));
		createSchema();
	}

	@BeforeEach
	void seedRows() {
		jdbc.update("TRUNCATE TABLE user_sanctions, reports, meeting_schedules, users");
		jdbc.update("INSERT INTO users(user_id, nickname) VALUES (1, 'reporter'), (2, 'reported'), (9, 'resolver')");
		insertReport(10, "message", null, null, null, 2L, "confirmed", "completed",
			"{\"schemaVersion\":1,\"reported\":{\"messageId\":1010,\"content\":\"snapshot\"}}");
		insertReport(11, "answer", null, 1111L, null, 2L, "pending", "cancelled",
			"{\"schemaVersion\":1,\"targetType\":\"answer\",\"reported\":{\"answerId\":1111,\"isAi\":false}}");
		insertReport(12, "answer", null, 1212L, null, null, "pending", "cancelled",
			"{\"schemaVersion\":1,\"targetType\":\"answer\",\"reported\":{\"answerId\":1212,\"authorId\":null,\"isAi\":true}}");
		insertReport(13, "schedule", null, null, 1313L, 2L, "pending", "cancelled",
			"{\"schemaVersion\":1,\"targetType\":\"schedule\",\"reported\":{\"scheduleId\":1313}}");
		jdbc.update("""
			INSERT INTO user_sanctions(
				sanction_id, user_id, report_id, decision_source, admin_id, sanction_type, reason,
				starts_at, ends_at, released_at, released_by, created_at
			) VALUES
				(100, 2, 10, 'ai_recommendation', NULL, 'temporary', 'AI sanction',
				 '2026-07-14T10:00:00Z', '2026-07-21T10:00:00Z', NULL, NULL, '2026-07-14T10:00:00Z'),
				(101, 2, 10, 'admin', 9, 'permanent', 'Admin sanction',
				 '2026-07-14T11:00:00Z', NULL, '2026-07-14T12:00:00Z', 9, '2026-07-14T11:00:00Z')
			""");
	}

	@Test
	void detailReadsDeletedTargetIdSnapshotAiReviewAndResolverWithoutWorkerLeaseColumns() {
		AdminReportDetailRow row = repository.findDetail(10L).orElseThrow();

		assertThat(row.targetType()).isEqualTo("message");
		assertThat(row.targetId()).isEqualTo(1010L);
		assertThat(row.targetDeleted()).isTrue();
		assertThat(row.reporterNickname()).isEqualTo("reporter");
		assertThat(row.reportedUserNickname()).isEqualTo("reported");
		assertThat(row.contextSnapshot()).contains("snapshot");
		assertThat(row.aiReviewResult()).contains("providerAttempts", "chainOfThought");
		assertThat(row.aiLastErrorCode()).isEqualTo("SAFE_CODE");
		assertThat(row.resolvedById()).isEqualTo(9L);
		assertThat(row.resolvedByNickname()).isEqualTo("resolver");
		assertThat(row.resolvedAt()).isNotNull();
	}

	@Test
	void detailSupportsHumanAndAiAnswersWithNullableReportedUser() {
		AdminReportDetailRow human = repository.findDetail(11L).orElseThrow();
		AdminReportDetailRow ai = repository.findDetail(12L).orElseThrow();

		assertThat(human.targetType()).isEqualTo("answer");
		assertThat(human.targetId()).isEqualTo(1111L);
		assertThat(human.reportedUserId()).isEqualTo(2L);
		assertThat(ai.targetId()).isEqualTo(1212L);
		assertThat(ai.reportedUserId()).isNull();
		assertThat(ai.reportedUserNickname()).isNull();
	}

	@Test
	void detailSupportsManualScheduleReports() {
		AdminReportDetailRow schedule = repository.findDetail(13L).orElseThrow();

		assertThat(schedule.targetType()).isEqualTo("schedule");
		assertThat(schedule.targetId()).isEqualTo(1313L);
		assertThat(schedule.targetDeleted()).isFalse();
		assertThat(schedule.reportedUserId()).isEqualTo(2L);
		assertThat(schedule.aiReviewState()).isEqualTo("cancelled");
	}

	@Test
	void detailMarksSoftDeletedScheduleTargetAsDeleted() {
		jdbc.update("INSERT INTO meeting_schedules(schedule_id, deleted_at) VALUES (1313, now())");

		AdminReportDetailRow schedule = repository.findDetail(13L).orElseThrow();

		assertThat(schedule.targetDeleted()).isTrue();
	}

	@Test
	void missingDetailIsEmpty() {
		assertThat(repository.findDetail(999L)).isEmpty();
	}

	@Test
	void sanctionHistoryIncludesResolverAndIsNewestFirst() {
		var sanctions = repository.findSanctions(10L);

		assertThat(sanctions).extracting(AdminReportSanctionRow::sanctionId).containsExactly(101L, 100L);
		assertThat(sanctions.getFirst().decisionSource()).isEqualTo("admin");
		assertThat(sanctions.getFirst().adminNickname()).isEqualTo("resolver");
		assertThat(sanctions.getFirst().releasedByNickname()).isEqualTo("resolver");
		assertThat(sanctions.getLast().decisionSource()).isEqualTo("ai_recommendation");
	}

	private static void createSchema() {
		jdbc.execute("CREATE TYPE report_target_type AS ENUM ('message', 'answer', 'schedule')");
		jdbc.execute("CREATE TYPE report_reason AS ENUM ('spam', 'ad', 'abuse', 'obscene', 'harassment', 'etc')");
		jdbc.execute("CREATE TYPE report_status AS ENUM ('pending', 'ai_reviewed', 'confirmed', 'dismissed')");
		jdbc.execute("CREATE TYPE ai_job_status AS ENUM ('pending', 'processing', 'retry', 'completed', 'cancelled', 'dead')");
		jdbc.execute("CREATE TYPE ai_report_decision AS ENUM ('suspend', 'hold', 'normal')");
		jdbc.execute("CREATE TYPE ai_recommendation AS ENUM ('temporary_suspend', 'hold', 'dismiss')");
		jdbc.execute("CREATE TYPE sanction_decision_source AS ENUM ('ai_recommendation', 'admin')");
		jdbc.execute("CREATE TYPE sanction_type AS ENUM ('temporary', 'permanent')");
		jdbc.execute("CREATE TABLE users(user_id BIGINT PRIMARY KEY, nickname VARCHAR(50) NOT NULL)");
		jdbc.execute("CREATE TABLE meeting_schedules(schedule_id BIGINT PRIMARY KEY, deleted_at TIMESTAMPTZ)");
		jdbc.execute("""
			CREATE TABLE reports (
				report_id BIGINT PRIMARY KEY, reporter_id BIGINT NOT NULL REFERENCES users(user_id),
				target_type report_target_type NOT NULL, message_id BIGINT, answer_id BIGINT, schedule_id BIGINT,
				reported_user_id BIGINT REFERENCES users(user_id), reason report_reason NOT NULL,
				detail TEXT, context_snapshot JSONB, context_hash CHAR(64) NOT NULL,
				ai_recommendation ai_recommendation, ai_reason TEXT, ai_confidence NUMERIC(5,4),
				ai_model_version VARCHAR(120), ai_policy_version VARCHAR(80), ai_reviewed_at TIMESTAMPTZ,
				ai_review_state ai_job_status NOT NULL, ai_review_attempt_id UUID, ai_attempts SMALLINT,
				ai_next_attempt_at TIMESTAMPTZ, ai_lease_until TIMESTAMPTZ, ai_locked_by VARCHAR(120),
				ai_last_error_code VARCHAR(80), ai_last_error_message VARCHAR(500), ai_decision ai_report_decision,
				ai_policy_set_hash CHAR(64), ai_review_result JSONB, status report_status NOT NULL,
				resolved_by BIGINT REFERENCES users(user_id), resolved_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL
			)
			""");
		jdbc.execute("""
			CREATE TABLE user_sanctions (
				sanction_id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(user_id),
				report_id BIGINT REFERENCES reports(report_id), decision_source sanction_decision_source NOT NULL,
				admin_id BIGINT REFERENCES users(user_id), sanction_type sanction_type NOT NULL, reason TEXT NOT NULL,
				starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ, released_at TIMESTAMPTZ,
				released_by BIGINT REFERENCES users(user_id), created_at TIMESTAMPTZ NOT NULL
			)
			""");
	}

	private void insertReport(
		long reportId,
		String targetType,
		Long messageId,
		Long answerId,
		Long scheduleId,
		Long reportedUserId,
		String status,
		String aiReviewState,
		String snapshot
	) {
		jdbc.update("""
			INSERT INTO reports(
					report_id, reporter_id, target_type, message_id, answer_id, schedule_id, reported_user_id, reason, detail,
				context_snapshot, context_hash, ai_recommendation, ai_reason, ai_confidence, ai_model_version,
				ai_policy_version, ai_reviewed_at, ai_review_state, ai_last_error_code, ai_last_error_message,
				ai_decision, ai_policy_set_hash, ai_review_result, status, resolved_by, resolved_at, created_at
				) VALUES (?, 1, ?::report_target_type, ?, ?, ?, ?, 'abuse', 'detail', ?::jsonb,
				 repeat('a', 64), 'temporary_suspend', 'safe reason', 0.9000, 'model-v1', 'policy-v1',
				 '2026-07-14T09:00:00Z', ?::ai_job_status, 'SAFE_CODE', 'SECRET_ERROR_MESSAGE', 'suspend',
				 repeat('b', 64),
				 '{"category":"abuse","severity":"high","providerAttempts":[{"raw":"SECRET"}],"chainOfThought":"SECRET"}'::jsonb,
				 ?::report_status,
				 CASE WHEN ? IN ('confirmed', 'dismissed') THEN 9 ELSE NULL END,
				 CASE WHEN ? IN ('confirmed', 'dismissed') THEN '2026-07-14T10:00:00Z'::timestamptz ELSE NULL END,
				 '2026-07-14T08:00:00Z')
			""",
			reportId, targetType, messageId, answerId, scheduleId, reportedUserId, snapshot, aiReviewState,
			status, status, status
		);
	}
}
