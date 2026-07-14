package shinhan.fibri.ieum.main.report.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;

@Component
public class ReportContextSnapshotFactory {

	private static final int MESSAGE_SCHEMA_VERSION = 1;
	private static final int ANSWER_SCHEMA_VERSION = 1;

	private final ObjectMapper objectMapper;
	private final JdbcClient jdbc;

	public ReportContextSnapshotFactory(ObjectMapper objectMapper, JdbcClient jdbc) {
		this.objectMapper = objectMapper;
		this.jdbc = jdbc;
	}

	public ReportContextSnapshot create(
		Long roomId,
		List<Message> before,
		Message reported,
		List<Message> after
	) {
		ContextSnapshotPayload payload = new ContextSnapshotPayload(
			MESSAGE_SCHEMA_VERSION,
			Objects.requireNonNull(roomId, "roomId must not be null"),
			Objects.requireNonNull(before, "before must not be null").stream().map(ContextMessage::from).toList(),
			ContextMessage.from(Objects.requireNonNull(reported, "reported must not be null")),
			Objects.requireNonNull(after, "after must not be null").stream().map(ContextMessage::from).toList()
		);
		try {
			return canonicalizeAndHash(objectMapper.writeValueAsString(payload));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize report context snapshot", exception);
		}
	}

	public ReportContextSnapshot createAnswer(Answer answer, List<AnswerImage> images) {
		Answer target = Objects.requireNonNull(answer, "answer must not be null");
		List<String> imageFileIds = Objects.requireNonNull(images, "images must not be null")
			.stream()
			.map(image -> image.getFileId().toString())
			.toList();
		AnswerContextSnapshotPayload payload = new AnswerContextSnapshotPayload(
			ANSWER_SCHEMA_VERSION,
			ReportTargetType.answer,
			target.getQuestionId(),
			AnswerContext.from(target, imageFileIds)
		);
		try {
			return canonicalizeAndHash(objectMapper.writeValueAsString(payload));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize answer report context snapshot", exception);
		}
	}

	private ReportContextSnapshot canonicalizeAndHash(String serializedSnapshot) {
		return jdbc.sql("""
			SELECT canonical.json,
			       encode(digest(convert_to(canonical.json, 'UTF8'), 'sha256'), 'hex') AS hash
			FROM (SELECT (CAST(:snapshot AS jsonb))::text AS json) canonical
			""")
			.param("snapshot", serializedSnapshot)
			.query((rs, rowNumber) -> new ReportContextSnapshot(rs.getString("json"), rs.getString("hash")))
			.single();
	}

	private record ContextSnapshotPayload(
		int schemaVersion,
		Long roomId,
		List<ContextMessage> before,
		ContextMessage reported,
		List<ContextMessage> after
	) {
	}

	private record AnswerContextSnapshotPayload(
		int schemaVersion,
		ReportTargetType targetType,
		Long questionId,
		AnswerContext reported
	) {
	}

	@JsonInclude(JsonInclude.Include.ALWAYS)
	private record AnswerContext(
		Long answerId,
		Long authorId,
		boolean isAi,
		String content,
		List<String> imageFileIds,
		OffsetDateTime createdAt
	) {

		private static AnswerContext from(Answer answer, List<String> imageFileIds) {
			return new AnswerContext(
				answer.getId(),
				answer.getAuthorId(),
				answer.isAi(),
				answer.getContent(),
				imageFileIds,
				answer.getCreatedAt()
			);
		}
	}

	@JsonInclude(JsonInclude.Include.ALWAYS)
	private record ContextMessage(
		Long messageId,
		Long senderId,
		String content,
		String imageFileId,
		OffsetDateTime createdAt
	) {

		private static ContextMessage from(Message message) {
			return new ContextMessage(
				message.getId(),
				message.getSender().getId(),
				message.getContent(),
				message.getImageFileId() == null ? null : message.getImageFileId().toString(),
				message.getCreatedAt()
			);
		}
	}
}
