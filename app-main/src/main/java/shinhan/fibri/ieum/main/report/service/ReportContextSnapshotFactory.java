package shinhan.fibri.ieum.main.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;

@Component
public class ReportContextSnapshotFactory {

	private static final int SCHEMA_VERSION = 1;

	private final ObjectMapper objectMapper;

	public ReportContextSnapshotFactory(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ReportContextSnapshot create(
		Long roomId,
		List<Message> before,
		Message reported,
		List<Message> after
	) {
		ContextSnapshotPayload payload = new ContextSnapshotPayload(
			SCHEMA_VERSION,
			Objects.requireNonNull(roomId, "roomId must not be null"),
			Objects.requireNonNull(before, "before must not be null").stream().map(ContextMessage::from).toList(),
			ContextMessage.from(Objects.requireNonNull(reported, "reported must not be null")),
			Objects.requireNonNull(after, "after must not be null").stream().map(ContextMessage::from).toList()
		);
		try {
			byte[] json = objectMapper.writeValueAsBytes(payload);
			return new ReportContextSnapshot(new String(json, StandardCharsets.UTF_8), sha256(json));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize report context snapshot", exception);
		}
	}

	private String sha256(byte[] source) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}

	private record ContextSnapshotPayload(
		int schemaVersion,
		Long roomId,
		List<ContextMessage> before,
		ContextMessage reported,
		List<ContextMessage> after
	) {
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
