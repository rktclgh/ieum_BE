package shinhan.fibri.ieum.main.admin.content.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.content.repository.ContentPurgeChunk;
import shinhan.fibri.ieum.main.admin.content.repository.ContentPurgeRepository;
import shinhan.fibri.ieum.main.file.service.S3FileDeletionService;

class ContentPurgeServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), KST);

	private final ContentPurgeRepository repository = mock(ContentPurgeRepository.class);
	private final S3FileDeletionService s3FileDeletionService = mock(S3FileDeletionService.class);
	private final ContentPurgeService service = new ContentPurgeService(repository, s3FileDeletionService, CLOCK);

	@Test
	void purgeUsesNinetyDayCutoffFiveHundredChunkSizeAndStopsWhenRepositoryReturnsEmpty() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenReturn(new ContentPurgeChunk(1, List.of("final/42/question/file/original.jpg")))
			.thenReturn(ContentPurgeChunk.empty());

		service.purgeExpiredQuestionContent();

		verify(repository, times(2)).purgeChunk(OffsetDateTime.now(CLOCK).minusDays(90), 500);
		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/42/question/file/original.jpg");
	}

	@Test
	void purgeCapsOneRunAtOneHundredChunks() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenReturn(new ContentPurgeChunk(1, List.of()));

		service.purgeExpiredQuestionContent();

		verify(repository, times(100)).purgeChunk(any(), eq(500));
	}

	@Test
	void purgePassesEachS3KeyToDeletionService() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenReturn(new ContentPurgeChunk(2, List.of(
				"final/42/question/first/original.jpg",
				"final/42/question/second/original.jpg"
			)))
			.thenReturn(ContentPurgeChunk.empty());

		assertThatCode(service::purgeExpiredQuestionContent).doesNotThrowAnyException();

		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/42/question/first/original.jpg");
		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/42/question/second/original.jpg");
	}

	@Test
	void malformedS3KeyIsDelegatedToDeletionService() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenReturn(new ContentPurgeChunk(2, List.of(
				"malformed-key",
				"final/42/question/second/original.jpg"
			)))
			.thenReturn(ContentPurgeChunk.empty());

		assertThatCode(service::purgeExpiredQuestionContent).doesNotThrowAnyException();

		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("malformed-key");
		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/42/question/second/original.jpg");
	}

	@Test
	void repositoryChunkFailureIsLoggedOnlyAndNextChunkIsTried() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenThrow(new IllegalStateException("db unavailable"))
			.thenReturn(new ContentPurgeChunk(1, List.of("final/42/question/recovered/original.jpg")))
			.thenReturn(ContentPurgeChunk.empty());

		assertThatCode(service::purgeExpiredQuestionContent).doesNotThrowAnyException();

		verify(repository, times(3)).purgeChunk(any(), eq(500));
		verify(s3FileDeletionService).deleteOriginAndVariantsLogOnly("final/42/question/recovered/original.jpg");
	}
}
