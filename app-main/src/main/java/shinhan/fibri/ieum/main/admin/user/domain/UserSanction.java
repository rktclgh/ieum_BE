package shinhan.fibri.ieum.main.admin.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(name = "user_sanctions")
public class UserSanction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "sanction_id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "report_id")
	private Long reportId;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "decision_source", nullable = false, columnDefinition = "sanction_decision_source")
	private SanctionDecisionSource decisionSource;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "sanction_type", nullable = false, columnDefinition = "sanction_type")
	private SanctionType type;

	@Column(nullable = false, columnDefinition = "text")
	private String reason;

	@Column(name = "ends_at")
	private OffsetDateTime endsAt;

	@Column(name = "starts_at", nullable = false)
	private OffsetDateTime startsAt;

	@Column(name = "duration_minutes")
	private Integer durationMinutes;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "review_status", nullable = false, columnDefinition = "sanction_review_status")
	private SanctionReviewStatus reviewStatus;

	@Column(name = "admin_id")
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "revoked_at")
	private OffsetDateTime revokedAt;

	@Column(name = "revoked_by")
	private Long revokedBy;

	@Column(name = "released_at")
	private OffsetDateTime releasedAt;

	@Column(name = "released_by")
	private Long releasedBy;

	protected UserSanction() {
	}

	private UserSanction(
		Long userId,
		Long reportId,
		SanctionDecisionSource decisionSource,
		SanctionType type,
		String reason,
		Long createdBy,
		OffsetDateTime createdAt,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		SanctionReviewStatus reviewStatus
	) {
		this.userId = Objects.requireNonNull(userId, "userId must not be null");
		this.reportId = reportId;
		this.decisionSource = Objects.requireNonNull(decisionSource, "decisionSource must not be null");
		this.type = Objects.requireNonNull(type, "type must not be null");
		this.reason = Objects.requireNonNull(reason, "reason must not be null");
		this.createdBy = createdBy;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
		this.startsAt = Objects.requireNonNull(startsAt, "startsAt must not be null");
		this.endsAt = endsAt;
		this.durationMinutes = calculateDurationMinutes(type, this.startsAt, this.endsAt);
		this.reviewStatus = Objects.requireNonNull(reviewStatus, "reviewStatus must not be null");
	}

	public static UserSanction temporary(Long userId, String reason, Long createdBy, OffsetDateTime endsAt) {
		if (endsAt == null) {
			throw new IllegalArgumentException("endsAt is required for temporary sanction");
		}
		OffsetDateTime startsAt = OffsetDateTime.now();
		return new UserSanction(
			userId,
			null,
			SanctionDecisionSource.admin,
			SanctionType.temporary,
			reason,
			Objects.requireNonNull(createdBy, "createdBy must not be null"),
			startsAt,
			startsAt,
			endsAt,
			SanctionReviewStatus.not_required
		);
	}

	public static UserSanction permanent(Long userId, String reason, Long createdBy) {
		OffsetDateTime createdAt = OffsetDateTime.now();
		return new UserSanction(
			userId,
			null,
			SanctionDecisionSource.admin,
			SanctionType.permanent,
			reason,
			Objects.requireNonNull(createdBy, "createdBy must not be null"),
			createdAt,
			createdAt,
			null,
			SanctionReviewStatus.not_required
		);
	}

	public static UserSanction aiTemporary(
		Long userId,
		Long reportId,
		String reason,
		OffsetDateTime createdAt,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt
	) {
		if (endsAt == null) {
			throw new IllegalArgumentException("endsAt is required for temporary sanction");
		}
		return new UserSanction(
			userId,
			Objects.requireNonNull(reportId, "reportId must not be null"),
			SanctionDecisionSource.ai_recommendation,
			SanctionType.temporary,
			reason,
			null,
			createdAt,
			startsAt,
			endsAt,
			SanctionReviewStatus.pending_review
		);
	}

	public void release(OffsetDateTime releasedAt, Long releasedBy) {
		if (this.revokedAt != null) {
			throw new IllegalStateException("sanction already released");
		}
		this.releasedAt = Objects.requireNonNull(releasedAt, "releasedAt must not be null");
		this.releasedBy = releasedBy;
		this.revokedAt = this.releasedAt;
		this.revokedBy = releasedBy;
	}

	public boolean isActive() {
		return revokedAt == null;
	}

	private static Integer calculateDurationMinutes(
		SanctionType type,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt
	) {
		if (type == SanctionType.permanent) {
			return null;
		}
		OffsetDateTime requiredEndsAt = Objects.requireNonNull(endsAt, "endsAt must not be null");
		if (!requiredEndsAt.isAfter(startsAt)) {
			throw new IllegalArgumentException("endsAt must be after startsAt");
		}
		long seconds = Duration.between(startsAt, requiredEndsAt).getSeconds();
		return Math.toIntExact(Math.max(1L, (seconds + 59L) / 60L));
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getReportId() {
		return reportId;
	}

	public SanctionDecisionSource getDecisionSource() {
		return decisionSource;
	}

	public SanctionType getType() {
		return type;
	}

	public String getReason() {
		return reason;
	}

	public OffsetDateTime getEndsAt() {
		return endsAt;
	}

	public OffsetDateTime getStartsAt() {
		return startsAt;
	}

	public Integer getDurationMinutes() {
		return durationMinutes;
	}

	public SanctionReviewStatus getReviewStatus() {
		return reviewStatus;
	}

	public Long getCreatedBy() {
		return createdBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getRevokedAt() {
		return revokedAt;
	}

	public Long getRevokedBy() {
		return revokedBy;
	}

	public OffsetDateTime getReleasedAt() {
		return releasedAt;
	}

	public Long getReleasedBy() {
		return releasedBy;
	}
}
