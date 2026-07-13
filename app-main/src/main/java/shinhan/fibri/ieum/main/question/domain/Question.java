package shinhan.fibri.ieum.main.question.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "questions")
@SQLRestriction("deleted_at IS NULL")
public class Question {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "question_id")
	private Long id;

	@Column(name = "pin_id", nullable = false)
	private Long pinId;

	@Column(name = "author_id", nullable = false)
	private Long authorId;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false)
	private String content;

	@Column(name = "is_resolved", nullable = false)
	private boolean resolved;

	@Column(name = "created_at", insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;

	protected Question() {
	}

	private Question(Long pinId, Long authorId, String title, String content) {
		this.pinId = Objects.requireNonNull(pinId, "pinId must not be null");
		this.authorId = Objects.requireNonNull(authorId, "authorId must not be null");
		this.title = Objects.requireNonNull(title, "title must not be null");
		this.content = Objects.requireNonNull(content, "content must not be null");
		this.resolved = false;
	}

	public static Question create(Long pinId, Long authorId, String title, String content) {
		return new Question(pinId, authorId, title, content);
	}

	public void markResolved() {
		this.resolved = true;
	}

	public void softDelete(OffsetDateTime deletedAt) {
		if (this.deletedAt == null) {
			this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
		}
	}

	public Long getId() {
		return id;
	}

	public Long getPinId() {
		return pinId;
	}

	public Long getAuthorId() {
		return authorId;
	}

	public String getTitle() {
		return title;
	}

	public String getContent() {
		return content;
	}

	public boolean isResolved() {
		return resolved;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	public OffsetDateTime getDeletedAt() {
		return deletedAt;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}
}
