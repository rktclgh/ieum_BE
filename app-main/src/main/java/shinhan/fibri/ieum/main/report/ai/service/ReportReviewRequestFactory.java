package shinhan.fibri.ieum.main.report.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewImage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.file.service.FileObjectKeys;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;

@Component
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class ReportReviewRequestFactory {

	private static final int SCHEMA_VERSION = 1;
	private static final int MAX_MESSAGES = 41;
	private static final Duration MAX_IMAGE_TTL = Duration.ofMinutes(10);

	private final ObjectMapper objectMapper;
	private final FileRepository fileRepository;
	private final FileStorage fileStorage;
	private final Duration imageTtl;

	public ReportReviewRequestFactory(
		ObjectMapper objectMapper,
		FileRepository fileRepository,
		FileStorage fileStorage,
		@Value("${app.ai.report.image-presign-ttl:5m}") Duration imageTtl
	) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository must not be null");
		this.fileStorage = Objects.requireNonNull(fileStorage, "fileStorage must not be null");
		if (imageTtl == null || imageTtl.isZero() || imageTtl.isNegative() || imageTtl.compareTo(MAX_IMAGE_TTL) > 0) {
			throw new IllegalArgumentException("imageTtl must be between one nanosecond and ten minutes");
		}
		this.imageTtl = imageTtl;
	}

	public ReportReviewRequest create(ClaimedReport claimed) {
		Objects.requireNonNull(claimed, "claimed must not be null");
		validateClaim(claimed);
		verifyHash(claimed.contextSnapshot(), claimed.contextHash());

		Snapshot snapshot = parseSnapshot(claimed.contextSnapshot());
		if (snapshot.reported().messageId() != claimed.reportedMessageId()) {
			throw permanent("REPORT_CONTEXT_TARGET_MISMATCH");
		}
		if (snapshot.reported().senderId() != claimed.reportedUserId()) {
			throw permanent("REPORT_CONTEXT_ACTOR_MISMATCH");
		}

		List<SnapshotMessage> chronological = chronological(snapshot);
		Map<UUID, File> images = loadImages(chronological);
		Map<Long, String> aliases = new HashMap<>();
		int[] nextOtherActor = {1};
		List<ReportReviewMessage> messages = chronological.stream()
			.map(message -> toRequestMessage(message, claimed, images, aliases, nextOtherActor))
			.toList();

		return new ReportReviewRequest(
			claimed.reportId(),
			claimed.attemptId(),
			claimed.reportedMessageId(),
			claimed.reason().name(),
			claimed.detail(),
			claimed.contextHash(),
			messages
		);
	}

	private void validateClaim(ClaimedReport claimed) {
		if (claimed.reportId() < 1 || claimed.reportedMessageId() == null || claimed.reportedMessageId() < 1
				|| claimed.reporterId() < 1 || claimed.reportedUserId() < 1 || claimed.reason() == null
				|| claimed.attemptId() == null || claimed.contextSnapshot() == null || claimed.contextHash() == null) {
			throw permanent("REPORT_CLAIM_INVALID");
		}
	}

	private void verifyHash(String snapshot, String expectedHash) {
		if (!expectedHash.matches("[0-9a-f]{64}") || !sha256(snapshot).equals(expectedHash)) {
			throw permanent("REPORT_CONTEXT_HASH_MISMATCH");
		}
	}

	private Snapshot parseSnapshot(String serialized) {
		try {
			JsonNode root = objectMapper.readTree(serialized);
			if (root == null || !root.isObject() || !hasSchemaVersion(root)
					|| !root.path("before").isArray() || !root.path("reported").isObject()
					|| !root.path("after").isArray()) {
				throw permanent("REPORT_CONTEXT_INVALID");
			}
			requiredPositiveLong(root, "roomId");
			List<SnapshotMessage> before = parseMessages(root.path("before"));
			SnapshotMessage reported = parseMessage(root.path("reported"));
			List<SnapshotMessage> after = parseMessages(root.path("after"));
			if (before.size() + after.size() + 1 > MAX_MESSAGES) {
				throw permanent("REPORT_CONTEXT_INVALID");
			}
			return new Snapshot(before, reported, after);
		} catch (ReportAiPermanentException exception) {
			throw exception;
		} catch (RuntimeException | java.io.IOException exception) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
	}

	private List<SnapshotMessage> parseMessages(JsonNode array) {
		List<SnapshotMessage> messages = new ArrayList<>(array.size());
		array.forEach(node -> messages.add(parseMessage(node)));
		return List.copyOf(messages);
	}

	private SnapshotMessage parseMessage(JsonNode node) {
		if (!node.isObject()) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
		long messageId = requiredPositiveLong(node, "messageId");
		long senderId = requiredPositiveLong(node, "senderId");
		String content = nullableText(node.get("content"));
		UUID imageFileId = nullableUuid(node.get("imageFileId"));
		String createdAt = createdAt(node.get("createdAt"));
		if ((content == null || content.isBlank()) && imageFileId == null) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
		return new SnapshotMessage(messageId, senderId, content, imageFileId, createdAt);
	}

	private long requiredPositiveLong(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
		return value.longValue();
	}

	private boolean hasSchemaVersion(JsonNode root) {
		JsonNode value = root.get("schemaVersion");
		return value != null
			&& value.isIntegralNumber()
			&& value.canConvertToInt()
			&& value.intValue() == SCHEMA_VERSION;
	}

	private String nullableText(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isTextual()) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
		return node.textValue();
	}

	private UUID nullableUuid(JsonNode node) {
		String value = nullableText(node);
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException exception) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
	}

	private String createdAt(JsonNode node) {
		if (node == null || node.isNull()) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
		try {
			if (node.isNumber()) {
				BigDecimal epoch = node.decimalValue();
				BigDecimal seconds = epoch.setScale(0, RoundingMode.DOWN);
				int nanos = epoch.subtract(seconds).movePointRight(9).intValueExact();
				return Instant.ofEpochSecond(seconds.longValueExact(), nanos).toString();
			}
			if (node.isTextual()) {
				return OffsetDateTime.parse(node.textValue()).toInstant().toString();
			}
		} catch (RuntimeException exception) {
			throw permanent("REPORT_CONTEXT_INVALID");
		}
		throw permanent("REPORT_CONTEXT_INVALID");
	}

	private List<SnapshotMessage> chronological(Snapshot snapshot) {
		List<SnapshotMessage> before = new ArrayList<>(snapshot.before());
		Collections.reverse(before);
		List<SnapshotMessage> result = new ArrayList<>(before.size() + snapshot.after().size() + 1);
		result.addAll(before);
		result.add(snapshot.reported());
		result.addAll(snapshot.after());
		Set<Long> messageIds = new LinkedHashSet<>();
		for (SnapshotMessage message : result) {
			if (!messageIds.add(message.messageId())) {
				throw permanent("REPORT_CONTEXT_INVALID");
			}
		}
		return List.copyOf(result);
	}

	private Map<UUID, File> loadImages(List<SnapshotMessage> messages) {
		List<UUID> imageIds = messages.stream()
			.map(SnapshotMessage::imageFileId)
			.filter(Objects::nonNull)
			.collect(java.util.stream.Collectors.collectingAndThen(
				java.util.stream.Collectors.toCollection(LinkedHashSet::new),
				List::copyOf
			));
		if (imageIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, File> byId = new HashMap<>();
		for (File file : fileRepository.findAllById(imageIds)) {
			if (file != null && file.isUploaded()) {
				byId.put(file.getFileId(), file);
			}
		}
		if (!byId.keySet().containsAll(imageIds)) {
			throw permanent("REPORT_CONTEXT_IMAGE_MISSING");
		}
		return Map.copyOf(byId);
	}

	private ReportReviewMessage toRequestMessage(
		SnapshotMessage message,
		ClaimedReport claimed,
		Map<UUID, File> images,
		Map<Long, String> aliases,
		int[] nextOtherActor
	) {
		String actor = aliases.computeIfAbsent(message.senderId(), senderId -> {
			if (senderId == claimed.reportedUserId()) {
				return "reported_user";
			}
			if (senderId == claimed.reporterId()) {
				return "reporter";
			}
			return "other_actor_" + nextOtherActor[0]++;
		});
		return new ReportReviewMessage(
			message.messageId(),
			actor,
			message.content(),
			requestImage(message.imageFileId(), images),
			message.createdAt()
		);
	}

	private ReportReviewImage requestImage(UUID imageFileId, Map<UUID, File> images) {
		if (imageFileId == null) {
			return null;
		}
		File file = images.get(imageFileId);
		String displayKey = FileObjectKeys.variantKey(file.getS3Key(), FileVariant.DISPLAY);
		URI url = fileStorage.createPresignedGetUrl(displayKey, imageTtl);
		if (url == null || !url.isAbsolute()) {
			throw permanent("REPORT_CONTEXT_IMAGE_URL_FAILED");
		}
		return new ReportReviewImage("image/webp", url.toString());
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private ReportAiPermanentException permanent(String errorCode) {
		return new ReportAiPermanentException(errorCode);
	}

	private record Snapshot(List<SnapshotMessage> before, SnapshotMessage reported, List<SnapshotMessage> after) {
	}

	private record SnapshotMessage(
		long messageId,
		long senderId,
		String content,
		UUID imageFileId,
		String createdAt
	) {
	}
}
