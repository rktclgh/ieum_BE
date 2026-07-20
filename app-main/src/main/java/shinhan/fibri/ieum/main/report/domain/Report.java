package shinhan.fibri.ieum.main.report.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;

@Entity
@Table(name = "reports")
public class Report {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "report_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reporter_id", nullable = false)
	private User reporter;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "message_id")
	private Message message;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "target_type", nullable = false, columnDefinition = "report_target_type")
	private ReportTargetType targetType;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "answer_id")
	private Answer answer;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "schedule_id")
	private MeetingSchedule schedule;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reported_user_id")
	private User reportedUser;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "reason", nullable = false, columnDefinition = "report_reason")
	private ReportReason reason;

	@Column(columnDefinition = "text")
	private String detail;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "context_snapshot", columnDefinition = "jsonb")
	private String contextSnapshot;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "context_hash", nullable = false, length = 64, columnDefinition = "char(64)")
	private String contextHash;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "ai_review_state", nullable = false, columnDefinition = "ai_job_status")
	private ReportAiReviewState aiReviewState;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "status", nullable = false, columnDefinition = "report_status")
	private ReportStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	protected Report() {
	}

	private Report(
		User reporter,
		ReportTargetType targetType,
		Message message,
		Answer answer,
		MeetingSchedule schedule,
		User reportedUser,
		ReportReason reason,
		String detail,
		ReportContextSnapshot contextSnapshot,
		ReportAiReviewState aiReviewState
	) {
		this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
		this.targetType = Objects.requireNonNull(targetType, "targetType must not be null");
		this.message = message;
		this.answer = answer;
		this.schedule = schedule;
		this.reportedUser = reportedUser;
		this.reason = Objects.requireNonNull(reason, "reason must not be null");
		this.detail = detail;
		this.contextSnapshot = Objects.requireNonNull(contextSnapshot, "contextSnapshot must not be null").json();
		this.contextHash = contextSnapshot.hash();
		this.aiReviewState = Objects.requireNonNull(aiReviewState, "aiReviewState must not be null");
		this.status = ReportStatus.pending;
		this.createdAt = OffsetDateTime.now();
	}

	public static Report messageReport(
		User reporter,
		Message message,
		ReportReason reason,
		String detail,
		ReportContextSnapshot contextSnapshot
	) {
		Message target = Objects.requireNonNull(message, "message must not be null");
		return new Report(
			reporter,
			ReportTargetType.message,
			target,
			null,
			null,
			target.getSender(),
			reason,
			detail,
			contextSnapshot,
			ReportAiReviewState.pending
		);
	}

	public static Report answerReport(
		User reporter,
		Answer answer,
		User reportedUser,
		ReportReason reason,
		String detail,
		ReportContextSnapshot contextSnapshot
	) {
		Answer target = Objects.requireNonNull(answer, "answer must not be null");
		validateAnswerReportedUser(target, reportedUser);
		return new Report(
			reporter,
			ReportTargetType.answer,
			null,
			target,
			null,
			reportedUser,
			reason,
			detail,
			contextSnapshot,
			ReportAiReviewState.cancelled
		);
	}

	public static Report scheduleReport(
		User reporter,
		MeetingSchedule schedule,
		User reportedUser,
		ReportReason reason,
		String detail,
		ReportContextSnapshot contextSnapshot
	) {
		MeetingSchedule target = Objects.requireNonNull(schedule, "schedule must not be null");
		User owner = Objects.requireNonNull(reportedUser, "reportedUser must not be null");
		if (!Objects.equals(target.getCreatedBy(), owner.getId())) {
			throw new IllegalArgumentException("reportedUser must match the schedule creator");
		}
		return new Report(
			reporter,
			ReportTargetType.schedule,
			null,
			null,
			target,
			owner,
			reason,
			detail,
			contextSnapshot,
			ReportAiReviewState.cancelled
		);
	}

	private static void validateAnswerReportedUser(Answer answer, User reportedUser) {
		if (answer.isAi()) {
			if (reportedUser != null) {
				throw new IllegalArgumentException("AI answer must not have a reportedUser");
			}
			return;
		}
		User author = Objects.requireNonNull(reportedUser, "reportedUser must not be null for a human answer");
		if (!Objects.equals(answer.getAuthorId(), author.getId())) {
			throw new IllegalArgumentException("reportedUser must match the answer author");
		}
	}

	public Long getId() {
		return id;
	}

	public User getReporter() {
		return reporter;
	}

	public Message getMessage() {
		return message;
	}

	public ReportTargetType getTargetType() {
		return targetType;
	}

	public Answer getAnswer() {
		return answer;
	}

	public MeetingSchedule getSchedule() {
		return schedule;
	}

	public User getReportedUser() {
		return reportedUser;
	}

	public ReportReason getReason() {
		return reason;
	}

	public String getDetail() {
		return detail;
	}

	public String getContextSnapshot() {
		return contextSnapshot;
	}

	public String getContextHash() {
		return contextHash;
	}

	public ReportAiReviewState getAiReviewState() {
		return aiReviewState;
	}

	public ReportStatus getStatus() {
		return status;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
