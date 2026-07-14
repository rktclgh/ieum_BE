package shinhan.fibri.ieum.main.admin.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "sanction_type", nullable = false, columnDefinition = "sanction_type")
	private SanctionType type;

	@Column(nullable = false, columnDefinition = "text")
	private String reason;

	@Column(name = "ends_at")
	private OffsetDateTime endsAt;

	@Column(name = "admin_id")
	private Long createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "released_at")
	private OffsetDateTime releasedAt;

	@Column(name = "released_by")
	private Long releasedBy;

	protected UserSanction() {
	}

	private UserSanction(Long userId, SanctionType type, String reason, Long createdBy, OffsetDateTime endsAt) {
		this.userId = Objects.requireNonNull(userId, "userId must not be null");
		this.type = Objects.requireNonNull(type, "type must not be null");
		this.reason = Objects.requireNonNull(reason, "reason must not be null");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
		this.endsAt = endsAt;
		this.createdAt = OffsetDateTime.now();
	}

	public static UserSanction temporary(Long userId, String reason, Long createdBy, OffsetDateTime endsAt) {
		if (endsAt == null) {
			throw new IllegalArgumentException("endsAt is required for temporary sanction");
		}
		return new UserSanction(userId, SanctionType.temporary, reason, createdBy, endsAt);
	}

	public static UserSanction permanent(Long userId, String reason, Long createdBy) {
		return new UserSanction(userId, SanctionType.permanent, reason, createdBy, null);
	}

	public void release(OffsetDateTime releasedAt, Long releasedBy) {
		if (this.releasedAt != null) {
			throw new IllegalStateException("sanction already released");
		}
		this.releasedAt = Objects.requireNonNull(releasedAt, "releasedAt must not be null");
		this.releasedBy = releasedBy;
	}

	public boolean isActive() {
		return releasedAt == null;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
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

	public Long getCreatedBy() {
		return createdBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getReleasedAt() {
		return releasedAt;
	}

	public Long getReleasedBy() {
		return releasedBy;
	}
}
