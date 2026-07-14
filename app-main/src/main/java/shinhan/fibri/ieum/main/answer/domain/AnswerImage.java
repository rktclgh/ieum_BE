package shinhan.fibri.ieum.main.answer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "answer_images")
public class AnswerImage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "image_id")
	private Long id;

	@Column(name = "answer_id", nullable = false)
	private Long answerId;

	@Column(name = "file_id", nullable = false)
	private UUID fileId;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	protected AnswerImage() {
	}

	private AnswerImage(Long answerId, UUID fileId, int sortOrder) {
		this.answerId = Objects.requireNonNull(answerId, "answerId must not be null");
		this.fileId = Objects.requireNonNull(fileId, "fileId must not be null");
		this.sortOrder = sortOrder;
	}

	public static AnswerImage link(Long answerId, UUID fileId, int sortOrder) {
		return new AnswerImage(answerId, fileId, sortOrder);
	}

	public Long getId() {
		return id;
	}

	public Long getAnswerId() {
		return answerId;
	}

	public UUID getFileId() {
		return fileId;
	}

	public int getSortOrder() {
		return sortOrder;
	}
}
