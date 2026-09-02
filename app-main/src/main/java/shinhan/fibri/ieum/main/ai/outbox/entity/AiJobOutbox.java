package shinhan.fibri.ieum.main.ai.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code ai_job_outbox} 매핑. spec.md §5.2/§5.3, db/migrations/v42_ai_job_outbox.sql.
 *
 * <p>이 태스크(Task 3)는 쓰기 경로만 담당한다 — 클레임/정산 컬럼(status 전이, lease, attempts 증가 등)은
 * Task 4(publisher relay)가 네이티브 SQL로 직접 다룬다. 이 엔티티는 그 컬럼들도 매핑하지만
 * 여기서는 {@link #pending} 으로 생성한 뒤 저장만 한다.
 *
 * <p>{@code job_type}/{@code status}/{@code routing_key}/{@code locked_by}는 Postgres enum이 아니라
 * TEXT 컬럼이므로 {@code @JdbcType(PostgreSQLEnumJdbcType.class)}를 쓰지 않는다(spec.md §5.3).
 */
@Entity
@Table(name = "ai_job_outbox")
public class AiJobOutbox {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "outbox_id")
	private Long id;

	@Column(name = "job_id", nullable = false, updatable = false)
	private UUID jobId;

	@Column(name = "job_type", nullable = false, updatable = false, columnDefinition = "text")
	private String jobType;

	@Column(name = "job_key", nullable = false, updatable = false)
	private Long jobKey;

	@Column(name = "routing_key", nullable = false, updatable = false, columnDefinition = "text")
	private String routingKey;

	@Column(name = "schema_version", nullable = false)
	private short schemaVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, columnDefinition = "jsonb")
	private String payload;

	@Column(name = "status", nullable = false, columnDefinition = "text")
	private String status;

	@Column(name = "attempts", nullable = false)
	private short attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private OffsetDateTime nextAttemptAt;

	@Column(name = "lease_token")
	private UUID leaseToken;

	@Column(name = "lease_until")
	private OffsetDateTime leaseUntil;

	@Column(name = "locked_by", columnDefinition = "text")
	private String lockedBy;

	@Column(name = "last_error_code", length = 80)
	private String lastErrorCode;

	@Column(name = "last_error_message", columnDefinition = "text")
	private String lastErrorMessage;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "published_at")
	private OffsetDateTime publishedAt;

	protected AiJobOutbox() {
	}

	private AiJobOutbox(UUID jobId, String jobType, Long jobKey, String routingKey, String payload) {
		this.jobId = Objects.requireNonNull(jobId, "jobId must not be null");
		this.jobType = Objects.requireNonNull(jobType, "jobType must not be null");
		this.jobKey = Objects.requireNonNull(jobKey, "jobKey must not be null");
		if (jobKey <= 0) {
			throw new IllegalArgumentException("jobKey must be positive: " + jobKey);
		}
		this.routingKey = Objects.requireNonNull(routingKey, "routingKey must not be null");
		this.payload = Objects.requireNonNull(payload, "payload must not be null");
		this.schemaVersion = 1;
		this.status = "pending";
		this.attempts = 0;
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		this.nextAttemptAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** pending 상태의 outbox row를 만든다. 도메인 트랜잭션 안에서 호출된다(spec.md §8.1). */
	public static AiJobOutbox pending(UUID jobId, String jobType, Long jobKey, String routingKey, String payload) {
		return new AiJobOutbox(jobId, jobType, jobKey, routingKey, payload);
	}

	public Long getId() {
		return id;
	}

	public UUID getJobId() {
		return jobId;
	}

	public String getJobType() {
		return jobType;
	}

	public Long getJobKey() {
		return jobKey;
	}

	public String getRoutingKey() {
		return routingKey;
	}

	public short getSchemaVersion() {
		return schemaVersion;
	}

	public String getPayload() {
		return payload;
	}

	public String getStatus() {
		return status;
	}

	public short getAttempts() {
		return attempts;
	}

	public OffsetDateTime getNextAttemptAt() {
		return nextAttemptAt;
	}

	public UUID getLeaseToken() {
		return leaseToken;
	}

	public OffsetDateTime getLeaseUntil() {
		return leaseUntil;
	}

	public String getLockedBy() {
		return lockedBy;
	}

	public String getLastErrorCode() {
		return lastErrorCode;
	}

	public String getLastErrorMessage() {
		return lastErrorMessage;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	public OffsetDateTime getPublishedAt() {
		return publishedAt;
	}
}
