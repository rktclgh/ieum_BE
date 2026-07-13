package shinhan.fibri.ieum.main.inquiry.domain;

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
@Table(name = "inquiries")
public class Inquiry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "inquiry_id")
	private Long id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private Long userId;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String content;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "inquiry_status")
	private InquiryStatus status;

	@Column(columnDefinition = "text")
	private String answer;

	@Column(name = "answered_by")
	private Long answeredBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "answered_at")
	private OffsetDateTime answeredAt;

	protected Inquiry() {
	}

	private Inquiry(Long userId, String title, String content) {
		this.userId = Objects.requireNonNull(userId, "userId must not be null");
		this.title = Objects.requireNonNull(title, "title must not be null");
		this.content = Objects.requireNonNull(content, "content must not be null");
		this.status = InquiryStatus.pending;
		this.createdAt = OffsetDateTime.now();
	}

	public static Inquiry create(Long userId, String title, String content) {
		return new Inquiry(userId, title, content);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public InquiryStatus getStatus() {
		return status;
	}

	public String getAnswer() {
		return answer;
	}

	public Long getAnsweredBy() {
		return answeredBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getAnsweredAt() {
		return answeredAt;
	}
}
