package shinhan.fibri.ieum.main.report.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;

class ReportReviewRequestFactoryTest {

	private static final Duration IMAGE_TTL = Duration.ofMinutes(5);
	private static final UUID IMAGE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private final FileRepository fileRepository = mock(FileRepository.class);
	private final FileStorage fileStorage = mock(FileStorage.class);
	private ReportReviewRequestFactory factory;

	@BeforeEach
	void setUp() {
		factory = new ReportReviewRequestFactory(new ObjectMapper(), fileRepository, fileStorage, IMAGE_TTL);
	}

	@Test
	void createsChronologicalAliasedRequestWithDisplayImage() {
		String snapshot = """
			{"after":[{"content":"after","senderId":20,"createdAt":1767225780.000000000,"messageId":4,"imageFileId":null}],"before":[{"content":"near","senderId":10,"createdAt":1767225660.000000000,"messageId":2,"imageFileId":null},{"content":"far","senderId":20,"createdAt":1767225600.000000000,"messageId":1,"imageFileId":null}],"roomId":100,"reported":{"content":null,"senderId":30,"createdAt":1767225720.000000000,"messageId":3,"imageFileId":"11111111-1111-1111-1111-111111111111"},"schemaVersion":1}
			""".strip();
		File image = File.pending(IMAGE_ID, 30L, "files/30/chat/image/origin.jpg", "image/jpeg", 100L);
		image.markUploaded(OffsetDateTime.parse("2026-01-01T00:00:00Z"), "image/jpeg", 100L);
		when(fileRepository.findAllById(List.of(IMAGE_ID))).thenReturn(List.of(image));
		when(fileStorage.createPresignedGetUrl("files/30/chat/image/display.webp", IMAGE_TTL))
			.thenReturn(URI.create("https://bucket.s3.ap-northeast-2.amazonaws.com/display.webp?signature=safe"));

		ReportReviewRequest request = factory.create(claimed(snapshot, sha256(snapshot)));

		assertThat(request.reportId()).isEqualTo(900L);
		assertThat(request.reviewAttemptId()).isEqualTo(UUID.fromString("22222222-2222-2222-2222-222222222222"));
		assertThat(request.reason()).isEqualTo("abuse");
		assertThat(request.messages()).extracting(message -> message.messageId())
			.containsExactly(1L, 2L, 3L, 4L);
		assertThat(request.messages()).extracting(message -> message.actor())
			.containsExactly("other_actor_1", "reporter", "reported_user", "other_actor_1");
		assertThat(request.messages().getFirst().createdAt()).isEqualTo("2026-01-01T00:00:00Z");
		assertThat(request.messages().get(2).image().contentType()).isEqualTo("image/webp");
		assertThat(request.messages().get(2).image().presignedGetUrl()).contains("display.webp?signature=safe");
		verify(fileStorage).createPresignedGetUrl("files/30/chat/image/display.webp", IMAGE_TTL);
	}

	@Test
	void rejectsTamperedSnapshotBeforeLoadingFiles() {
		String snapshot = """
			{"after":[],"before":[],"roomId":100,"reported":{"content":"reported","senderId":30,"createdAt":1767225720.000000000,"messageId":3,"imageFileId":null},"schemaVersion":1}
			""".strip();

		assertThatThrownBy(() -> factory.create(claimed(snapshot, "a".repeat(64))))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_CONTEXT_HASH_MISMATCH");
		verifyNoInteractions(fileRepository, fileStorage);
	}

	@Test
	void rejectsMissingUploadedImageInsteadOfDroppingEvidence() {
		String snapshot = """
			{"after":[],"before":[],"roomId":100,"reported":{"content":null,"senderId":30,"createdAt":1767225720.000000000,"messageId":3,"imageFileId":"11111111-1111-1111-1111-111111111111"},"schemaVersion":1}
			""".strip();
		when(fileRepository.findAllById(List.of(IMAGE_ID))).thenReturn(List.of());

		assertThatThrownBy(() -> factory.create(claimed(snapshot, sha256(snapshot))))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_CONTEXT_IMAGE_MISSING");
		verifyNoInteractions(fileStorage);
	}

	@Test
	void rejectsFractionalSnapshotIdentifiers() {
		List<String> snapshots = List.of(
			"""
				{"after":[],"before":[],"roomId":100,"reported":{"content":"reported","senderId":30,"createdAt":1767225720,"messageId":3,"imageFileId":null},"schemaVersion":1.5}
				""".strip(),
			"""
				{"after":[],"before":[],"roomId":100.5,"reported":{"content":"reported","senderId":30,"createdAt":1767225720,"messageId":3,"imageFileId":null},"schemaVersion":1}
				""".strip(),
			"""
				{"after":[],"before":[],"roomId":100,"reported":{"content":"reported","senderId":30,"createdAt":1767225720,"messageId":3.5,"imageFileId":null},"schemaVersion":1}
				""".strip(),
			"""
				{"after":[],"before":[],"roomId":100,"reported":{"content":"reported","senderId":30.5,"createdAt":1767225720,"messageId":3,"imageFileId":null},"schemaVersion":1}
				""".strip()
		);

		for (String snapshot : snapshots) {
			assertThatThrownBy(() -> factory.create(claimed(snapshot, sha256(snapshot))))
				.isInstanceOf(ReportAiPermanentException.class)
				.hasMessage("REPORT_CONTEXT_INVALID");
		}
		verifyNoInteractions(fileRepository, fileStorage);
	}

	@Test
	void rejectsEpochTimestampMorePreciseThanNanoseconds() {
		String snapshot = """
			{"after":[],"before":[],"roomId":100,"reported":{"content":"reported","senderId":30,"createdAt":1767225720.1234567891,"messageId":3,"imageFileId":null},"schemaVersion":1}
			""".strip();
		ReportReviewRequestFactory preciseFactory = new ReportReviewRequestFactory(
			new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
			fileRepository,
			fileStorage,
			IMAGE_TTL
		);

		assertThatThrownBy(() -> preciseFactory.create(claimed(snapshot, sha256(snapshot))))
			.isInstanceOf(ReportAiPermanentException.class)
			.hasMessage("REPORT_CONTEXT_INVALID");
		verifyNoInteractions(fileRepository, fileStorage);
	}

	private ClaimedReport claimed(String snapshot, String hash) {
		return new ClaimedReport(
			900L,
			3L,
			10L,
			30L,
			ReportReason.abuse,
			"detail",
			snapshot,
			hash,
			UUID.fromString("22222222-2222-2222-2222-222222222222"),
			1,
			OffsetDateTime.now().plusMinutes(2)
		);
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}
}
