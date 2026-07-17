package shinhan.fibri.ieum.main.admin.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import shinhan.fibri.ieum.main.admin.report.repository.AdminReportRepository.AdminReportListRow;
import shinhan.fibri.ieum.main.admin.report.service.AdminReportCursor;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;

class AdminReportRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_admin_report_list";
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
		jdbc.update("TRUNCATE TABLE reports, meeting_schedules, users");
		jdbc.update("INSERT INTO users(user_id, nickname) VALUES (1, 'reporter'), (2, 'reported')");
		insertReport(1, "message", 101L, null, null, 2L, "spam", "pending", "pending", null, null,
			"2026-07-14T10:00:00.123456789+09:00", "{\"reported\":{\"messageId\":101}}");
		insertReport(2, "answer", null, 202L, null, 2L, "abuse", "ai_reviewed", "completed", "suspend",
			new BigDecimal("0.9100"), "2026-07-14T10:00:00.123456789+09:00",
			"{\"reported\":{\"answerId\":202,\"isAi\":false}}");
		insertReport(3, "answer", null, 203L, null, null, "etc", "dismissed", "cancelled", "normal", null,
			"2026-07-14T11:00:00.000000001+09:00", "{\"reported\":{\"answerId\":203,\"isAi\":true}}");
		insertReport(4, "message", null, null, null, 2L, "harassment", "pending", "retry", null, null,
			"2026-07-14T12:00:00.000000002+09:00", "{\"reported\":{\"messageId\":404}}");
		insertReport(5, "answer", null, null, null, 2L, "obscene", "pending", "cancelled", null, null,
			"2026-07-14T13:00:00.000000003+09:00", "{\"reported\":{\"answerId\":505,\"isAi\":false}}");
		insertReport(6, "schedule", null, null, 606L, 2L, "ad", "pending", "cancelled", null, null,
			"2026-07-14T14:00:00.000000004+09:00", "{\"reported\":{\"scheduleId\":606}}");
	}

	@Test
	void nullFiltersExecuteAgainstNativeEnumsWithoutPostgres42P18() {
		List<AdminReportListRow> rows = repository.findReports(null, null, null, null, 10);

		assertThat(rows).extracting(AdminReportListRow::reportId).containsExactly(6L, 5L, 4L, 3L, 2L, 1L);
	}

	@Test
	void appliesEveryOptionalFilterOnlyWhenPresent() {
		assertThat(repository.findReports("ai_reviewed", null, null, null, 10))
			.extracting(AdminReportListRow::reportId).containsExactly(2L);
		assertThat(repository.findReports(null, "retry", null, null, 10))
			.extracting(AdminReportListRow::reportId).containsExactly(4L);
		assertThat(repository.findReports(null, null, "normal", null, 10))
			.extracting(AdminReportListRow::reportId).containsExactly(3L);
	}

	@Test
	void returnsMessageHumanAnswerAiAnswerAndDeletedTargetIds() {
		List<AdminReportListRow> rows = repository.findReports(null, null, null, null, 10);

		assertThat(row(rows, 1L).targetType()).isEqualTo("message");
		assertThat(row(rows, 1L).targetId()).isEqualTo(101L);
		assertThat(row(rows, 1L).targetDeleted()).isFalse();
		assertThat(row(rows, 2L).targetType()).isEqualTo("answer");
		assertThat(row(rows, 2L).targetId()).isEqualTo(202L);
		assertThat(row(rows, 2L).reportedUserId()).isEqualTo(2L);
		assertThat(row(rows, 3L).targetId()).isEqualTo(203L);
		assertThat(row(rows, 3L).reportedUserId()).isNull();
		assertThat(row(rows, 4L).targetId()).isEqualTo(404L);
		assertThat(row(rows, 4L).targetDeleted()).isTrue();
		assertThat(row(rows, 5L).targetId()).isEqualTo(505L);
		assertThat(row(rows, 5L).targetDeleted()).isTrue();
		assertThat(row(rows, 6L).targetType()).isEqualTo("schedule");
		assertThat(row(rows, 6L).targetId()).isEqualTo(606L);
		assertThat(row(rows, 6L).targetDeleted()).isFalse();
	}

	@Test
	void marksSoftDeletedScheduleTargetAsDeleted() {
		jdbc.update("INSERT INTO meeting_schedules(schedule_id, deleted_at) VALUES (606, now())");

		AdminReportListRow schedule = row(repository.findReports(null, null, null, null, 10), 6L);

		assertThat(schedule.targetDeleted()).isTrue();
	}

	@Test
	void keysetPagingIsDuplicateFreeWhenRowsShareExactNanosecondTimestamp() {
		List<AdminReportListRow> first = repository.findReports(null, null, null, null, 4);
		AdminReportListRow last = first.getLast();
		var cursor = new AdminReportCursor.Position(last.createdAt(), last.reportId());

		List<AdminReportListRow> second = repository.findReports(null, null, null, cursor, 4);

		assertThat(first).extracting(AdminReportListRow::reportId).containsExactly(6L, 5L, 4L, 3L);
		assertThat(second).extracting(AdminReportListRow::reportId).containsExactly(2L, 1L);
		assertThat(first.stream().map(AdminReportListRow::reportId))
			.doesNotContainAnyElementsOf(second.stream().map(AdminReportListRow::reportId).toList());
	}

	@Test
	void exposesSafeAiSummaryColumnsNeededByTheList() {
		AdminReportListRow reviewed = row(repository.findReports(null, null, null, null, 10), 2L);

		assertThat(reviewed.aiReviewState()).isEqualTo("completed");
		assertThat(reviewed.aiDecision()).isEqualTo("suspend");
		assertThat(reviewed.aiConfidence()).isEqualByComparingTo("0.9100");
		assertThat(reviewed.aiReviewedAt()).isNotNull();
	}

	private static void createSchema() {
		jdbc.execute("CREATE TYPE report_target_type AS ENUM ('message', 'answer', 'schedule')");
		jdbc.execute("CREATE TYPE report_reason AS ENUM ('spam', 'ad', 'abuse', 'obscene', 'harassment', 'etc')");
		jdbc.execute("CREATE TYPE report_status AS ENUM ('pending', 'ai_reviewed', 'confirmed', 'dismissed')");
		jdbc.execute("CREATE TYPE ai_job_status AS ENUM ('pending', 'processing', 'retry', 'completed', 'cancelled', 'dead')");
		jdbc.execute("CREATE TYPE ai_report_decision AS ENUM ('suspend', 'hold', 'normal')");
		jdbc.execute("CREATE TYPE ai_recommendation AS ENUM ('temporary_suspend', 'hold', 'dismiss')");
		jdbc.execute("""
			CREATE TABLE users (
				user_id BIGINT PRIMARY KEY,
				nickname VARCHAR(50) NOT NULL
			)
			""");
		jdbc.execute("CREATE TABLE meeting_schedules(schedule_id BIGINT PRIMARY KEY, deleted_at TIMESTAMPTZ)");
		jdbc.execute("""
			CREATE TABLE reports (
				report_id BIGINT PRIMARY KEY,
				reporter_id BIGINT NOT NULL REFERENCES users(user_id),
				target_type report_target_type NOT NULL,
				message_id BIGINT,
				answer_id BIGINT,
				schedule_id BIGINT,
				reported_user_id BIGINT REFERENCES users(user_id),
				reason report_reason NOT NULL,
				status report_status NOT NULL,
				ai_review_state ai_job_status NOT NULL,
				ai_recommendation ai_recommendation,
				ai_decision ai_report_decision,
				ai_confidence NUMERIC(5,4),
				ai_reviewed_at TIMESTAMPTZ,
				context_snapshot JSONB NOT NULL,
				created_at TIMESTAMPTZ NOT NULL
			)
			""");
	}

	private void insertReport(
		long id,
		String targetType,
		Long messageId,
		Long answerId,
		Long scheduleId,
		Long reportedUserId,
		String reason,
		String status,
		String aiReviewState,
		String aiDecision,
		BigDecimal confidence,
		String createdAt,
		String snapshot
	) {
		jdbc.update(
			"""
				INSERT INTO reports(
					 report_id, reporter_id, target_type, message_id, answer_id, schedule_id, reported_user_id,
					reason, status, ai_review_state, ai_recommendation, ai_decision, ai_confidence,
					ai_reviewed_at, context_snapshot, created_at
				) VALUES (?, 1, ?::report_target_type, ?, ?, ?, ?, ?::report_reason, ?::report_status,
					?::ai_job_status, CASE WHEN ? = 'suspend' THEN 'temporary_suspend'::ai_recommendation ELSE NULL END,
					?::ai_report_decision, ?, CASE WHEN CAST(? AS varchar) IS NULL THEN NULL ELSE ?::timestamptz END,
					?::jsonb, ?::timestamptz)
				""",
			id, targetType, messageId, answerId, scheduleId, reportedUserId, reason, status, aiReviewState,
			aiDecision, aiDecision, confidence, aiDecision, createdAt, snapshot, createdAt
		);
	}

	private AdminReportListRow row(List<AdminReportListRow> rows, Long reportId) {
		return rows.stream().filter(row -> row.reportId().equals(reportId)).findFirst().orElseThrow();
	}
}
