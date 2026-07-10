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

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reported_user_id", nullable = false)
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
		Message message,
		User reportedUser,
		ReportReason reason,
		String detail,
		String contextSnapshot
	) {
		this.reporter = Objects.requireNonNull(reporter, "reporter must not be null");
		this.message = Objects.requireNonNull(message, "message must not be null");
		this.reportedUser = Objects.requireNonNull(reportedUser, "reportedUser must not be null");
		this.reason = Objects.requireNonNull(reason, "reason must not be null");
		this.detail = detail;
		this.contextSnapshot = contextSnapshot;
		this.status = ReportStatus.pending;
		this.createdAt = OffsetDateTime.now();
	}

	public static Report messageReport(
		User reporter,
		Message message,
		ReportReason reason,
		String detail,
		String contextSnapshot
	) {
		return new Report(reporter, message, message.getSender(), reason, detail, contextSnapshot);
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

	public ReportStatus getStatus() {
		return status;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
