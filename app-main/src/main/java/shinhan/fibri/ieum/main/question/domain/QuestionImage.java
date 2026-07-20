package shinhan.fibri.ieum.main.question.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "question_images")
public class QuestionImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "image_id")
	private Long id;

	@Column(name = "question_id", nullable = false)
	private Long questionId;

	@Column(name = "file_id", nullable = false)
	private UUID fileId;

	@Column(name = "sort_order", nullable = false)
	private short sortOrder;

	protected QuestionImage() {
	}

	private QuestionImage(Long questionId, UUID fileId, short sortOrder) {
		this.questionId = Objects.requireNonNull(questionId, "questionId must not be null");
		this.fileId = Objects.requireNonNull(fileId, "fileId must not be null");
		this.sortOrder = sortOrder;
	}

	public static QuestionImage link(Long questionId, UUID fileId, int sortOrder) {
		if (sortOrder < 0 || sortOrder > Short.MAX_VALUE) {
			throw new IllegalArgumentException("sortOrder must be between 0 and " + Short.MAX_VALUE);
		}
		return new QuestionImage(questionId, fileId, (short) sortOrder);
	}

	public Long getId() {
		return id;
	}

	public Long getQuestionId() {
		return questionId;
	}

	public UUID getFileId() {
		return fileId;
	}

	public int getSortOrder() {
		return sortOrder;
	}
}
