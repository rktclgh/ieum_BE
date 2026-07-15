package shinhan.fibri.ieum.main.report.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;
import shinhan.fibri.ieum.main.ai.client.AiServiceClient;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;
import shinhan.fibri.ieum.main.report.repository.ReportAiWorkRepository;

class ReportAiWorkProcessorTest {

	private static final Instant NOW = Instant.parse("2026-07-14T03:00:00Z");
	private final ReportAiWorkRepository repository = mock(ReportAiWorkRepository.class);
	private final ReportReviewRequestFactory requestFactory = mock(ReportReviewRequestFactory.class);
	private final AiServiceClient aiServiceClient = mock(AiServiceClient.class);
	private final ReportAiResultApplier resultApplier = mock(ReportAiResultApplier.class);
	private final ReportAiWorkerProperties properties = new ReportAiWorkerProperties(
		"worker-a", Duration.ofSeconds(150), 5, 32
	);
	private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
	private ReportAiWorkProcessor processor;

	@BeforeEach
	void setUp() {
		processor = new ReportAiWorkProcessor(
			repository,
			requestFactory,
			aiServiceClient,
			resultApplier,
			new ReportAiRetryPolicy(),
			properties,
			clock
		);
	}

	@Test
	void completesOneClaimedReportAndWritesSafeLifecycleLogs() {
		ClaimedReport claimed = claimed(1);
		ReportReviewRequest request = mock(ReportReviewRequest.class);
		ReportReviewResponse response = mock(ReportReviewResponse.class);
		when(response.decision()).thenReturn("suspend");
		when(repository.claimNext("worker-a", Duration.ofSeconds(150), 5)).thenReturn(Optional.of(claimed));
		when(requestFactory.create(claimed)).thenReturn(request);
		when(aiServiceClient.review(request)).thenReturn(response);
		when(resultApplier.apply(claimed, response))
			.thenReturn(ReportAiApplyOutcome.completed("suspend", true));
		ListAppender<ILoggingEvent> logs = captureLogs();

		assertThat(processor.processNext()).isTrue();

		verify(resultApplier).apply(claimed, response);
		assertThat(messages(logs))
			.contains("event=report_ai_claimed reportId=900 attemptId=22222222-2222-2222-2222-222222222222 workerId=worker-a attempts=1")
			.anyMatch(message -> message.contains("event=report_ai_completed reportId=900")
				&& message.contains("decision=suspend") && message.contains("sanctioned=true")
				&& message.contains("durationMs="));
		assertThat(messages(logs)).noneMatch(message -> message.contains("private detail") || message.contains("reported content"));
	}

	@Test
	void schedulesRetryForTransportFailureWithoutLoggingExceptionMessage() {
		ClaimedReport claimed = claimed(1);
		ReportReviewRequest request = mock(ReportReviewRequest.class);
		when(repository.claimNext(any(), any(), eq(5))).thenReturn(Optional.of(claimed));
		when(requestFactory.create(claimed)).thenReturn(request);
		when(aiServiceClient.review(request)).thenThrow(new ResourceAccessException("secret internal URL"));
		when(repository.markRetry(
			eq(900L), eq(claimed.attemptId()), eq(OffsetDateTime.ofInstant(NOW.plusSeconds(10), ZoneOffset.UTC)),
			eq("REPORT_AI_TRANSPORT_FAILURE"), eq("Report AI processing failed")
		)).thenReturn(true);
		ListAppender<ILoggingEvent> logs = captureLogs();

		assertThat(processor.processNext()).isTrue();

		verify(repository).markRetry(
			900L,
			claimed.attemptId(),
			OffsetDateTime.ofInstant(NOW.plusSeconds(10), ZoneOffset.UTC),
			"REPORT_AI_TRANSPORT_FAILURE",
			"Report AI processing failed"
		);
		assertThat(messages(logs)).anyMatch(message -> message.contains("event=report_ai_retry_scheduled")
			&& message.contains("errorCode=REPORT_AI_TRANSPORT_FAILURE"));
		assertThat(messages(logs)).noneMatch(message -> message.contains("secret internal URL"));
	}

	@Test
	void marksPermanentSnapshotFailureDead() {
		ClaimedReport claimed = claimed(1);
		when(repository.claimNext(any(), any(), eq(5))).thenReturn(Optional.of(claimed));
		when(requestFactory.create(claimed)).thenThrow(new ReportAiPermanentException("REPORT_CONTEXT_INVALID"));
		when(repository.markDead(
			900L, claimed.attemptId(), "REPORT_CONTEXT_INVALID", "Report AI processing failed"
		)).thenReturn(true);

		assertThat(processor.processNext()).isTrue();

		verify(repository).markDead(
			900L, claimed.attemptId(), "REPORT_CONTEXT_INVALID", "Report AI processing failed"
		);
		verify(repository, never()).markRetry(any(Long.class), any(), any(), any(), any());
	}

	@Test
	void marksTheFifthRetryableFailureDead() {
		ClaimedReport claimed = claimed(5);
		ReportReviewRequest request = mock(ReportReviewRequest.class);
		when(repository.claimNext(any(), any(), eq(5))).thenReturn(Optional.of(claimed));
		when(requestFactory.create(claimed)).thenReturn(request);
		when(aiServiceClient.review(request)).thenThrow(new ResourceAccessException("timeout"));
		when(repository.markDead(
			900L, claimed.attemptId(), "REPORT_AI_MAX_ATTEMPTS", "Report AI processing failed"
		)).thenReturn(true);

		assertThat(processor.processNext()).isTrue();

		verify(repository).markDead(
			900L, claimed.attemptId(), "REPORT_AI_MAX_ATTEMPTS", "Report AI processing failed"
		);
	}

	private ClaimedReport claimed(int attempts) {
		return new ClaimedReport(
			900L, 3L, 10L, 30L, ReportReason.abuse, "private detail",
			"{\"reported\":{\"content\":\"reported content\"}}", "a".repeat(64),
			UUID.fromString("22222222-2222-2222-2222-222222222222"), attempts,
			OffsetDateTime.ofInstant(NOW.plusSeconds(150), ZoneOffset.UTC)
		);
	}

	private ListAppender<ILoggingEvent> captureLogs() {
		Logger logger = (Logger) LoggerFactory.getLogger(ReportAiWorkProcessor.class);
		logger.setLevel(Level.INFO);
		logger.setAdditive(false);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private java.util.List<String> messages(ListAppender<ILoggingEvent> appender) {
		return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
	}
}
