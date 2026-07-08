package shinhan.fibri.ieum.main.answer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "answers")
public class Answer {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "answer_id")
	private Long id;

	@Column(name = "question_id", nullable = false)
	private Long questionId;

	@Column(name = "author_id")
	private Long authorId;

	@Column(name = "is_ai", nullable = false)
	private boolean ai;

	// 운영 DB의 answers.content는 text NOT NULL(길이 제한 없음) → JPA 기본 length=255 metadata가
	// CreateAnswerRequest의 @Size(max=5000)과 어긋나지 않도록 실제 컬럼 타입과 동일하게 명시한다.
	// content/imageFileIds 중 하나만 있어도 되지만 DB가 NOT NULL이라 서비스에서 null을 빈 문자열로
	// 정규화해 넣는다(AnswerService.requireContentOrImages) — 엔티티를 nullable로 바꾸면 실제 NOT NULL
	// 제약 위반으로 insert가 실패하므로 nullable=false는 유지한다.
	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(name = "is_accepted", nullable = false)
	private boolean accepted;

	@Column(name = "created_at", insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private OffsetDateTime updatedAt;

	protected Answer() {
	}

	private Answer(Long questionId, Long authorId, boolean ai, String content) {
		this.questionId = Objects.requireNonNull(questionId, "questionId must not be null");
		this.authorId = authorId;
		this.ai = ai;
		this.content = Objects.requireNonNull(content, "content must not be null");
		this.accepted = false;
	}

	public static Answer createHuman(Long questionId, Long authorId, String content) {
		return new Answer(
			questionId,
			Objects.requireNonNull(authorId, "authorId must not be null"),
			false,
			content
		);
	}

	public static Answer createAi(Long questionId, String content) {
		return new Answer(questionId, null, true, content);
	}

	public void accept() {
		this.accepted = true;
	}

	public Long getId() {
		return id;
	}

	public Long getQuestionId() {
		return questionId;
	}

	public Long getAuthorId() {
		return authorId;
	}

	public boolean isAi() {
		return ai;
	}

	public String getContent() {
		return content;
	}

	public boolean isAccepted() {
		return accepted;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}
}
