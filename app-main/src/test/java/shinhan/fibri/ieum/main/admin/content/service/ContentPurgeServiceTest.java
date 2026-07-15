package shinhan.fibri.ieum.main.admin.content.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import shinhan.fibri.ieum.main.file.storage.FileStorage;

class ContentPurgeServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-15T01:00:00Z"), KST);

	private final ContentPurgeRepository repository = mock(ContentPurgeRepository.class);
	private final FileStorage fileStorage = mock(FileStorage.class);
	private final ContentPurgeService service = new ContentPurgeService(repository, fileStorage, CLOCK);

	@Test
	void purgeUsesNinetyDayCutoffFiveHundredChunkSizeAndStopsWhenRepositoryReturnsEmpty() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenReturn(new ContentPurgeChunk(1, List.of("final/42/question/file/original.jpg")))
			.thenReturn(ContentPurgeChunk.empty());

		service.purgeExpiredQuestionContent();

		verify(repository, times(2)).purgeChunk(OffsetDateTime.now(CLOCK).minusDays(90), 500);
		verify(fileStorage).delete("final/42/question/file/original.jpg");
		verify(fileStorage).delete("final/42/question/file/display.webp");
		verify(fileStorage).delete("final/42/question/file/thumb.webp");
	}

	@Test
	void purgeCapsOneRunAtOneHundredChunks() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenReturn(new ContentPurgeChunk(1, List.of()));

		service.purgeExpiredQuestionContent();

		verify(repository, times(100)).purgeChunk(any(), eq(500));
	}

	@Test
	void s3DeleteFailureIsLoggedOnlyAndDoesNotStopLaterKeys() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenReturn(new ContentPurgeChunk(2, List.of(
				"final/42/question/first/original.jpg",
				"final/42/question/second/original.jpg"
			)))
			.thenReturn(ContentPurgeChunk.empty());
		doThrow(new IllegalStateException("s3 unavailable"))
			.when(fileStorage).delete("final/42/question/first/original.jpg");

		assertThatCode(service::purgeExpiredQuestionContent).doesNotThrowAnyException();

		verify(fileStorage).delete("final/42/question/second/original.jpg");
	}

	@Test
	void repositoryChunkFailureIsLoggedOnlyAndNextChunkIsTried() {
		when(repository.purgeChunk(any(), eq(500)))
			.thenThrow(new IllegalStateException("db unavailable"))
			.thenReturn(new ContentPurgeChunk(1, List.of("final/42/question/recovered/original.jpg")))
			.thenReturn(ContentPurgeChunk.empty());

		assertThatCode(service::purgeExpiredQuestionContent).doesNotThrowAnyException();

		verify(repository, times(3)).purgeChunk(any(), eq(500));
		verify(fileStorage).delete("final/42/question/recovered/original.jpg");
	}
}
