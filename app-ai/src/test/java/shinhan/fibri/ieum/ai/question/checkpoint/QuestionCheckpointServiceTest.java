package shinhan.fibri.ieum.ai.question.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;

class QuestionCheckpointServiceTest {

	private final QuestionCheckpointRepository repository = mock(QuestionCheckpointRepository.class);
	private final QuestionCheckpointService service = new QuestionCheckpointService(repository);

	@Test
	void locksTicketBeforeQuestionAndPersistsAnalysis() {
		ClaimedQuestionTask claim = claim();
		QueryAnalysis analysis = analysis();
		Duration extension = Duration.ofMinutes(2);
		when(repository.lockCurrentFence(claim))
			.thenReturn(Optional.of(new LockedQuestionCheckpoint(false)));
		when(repository.lockActiveQuestion(claim.questionId())).thenReturn(true);
		when(repository.saveAnalysis(claim, analysis, extension)).thenReturn(true);

		QuestionCheckpointResult result = service.saveAnalysis(claim, analysis, extension);

		assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
		InOrder order = inOrder(repository);
		order.verify(repository).lockCurrentFence(claim);
		order.verify(repository).lockActiveQuestion(claim.questionId());
		order.verify(repository).saveAnalysis(claim, analysis, extension);
		order.verifyNoMoreInteractions();
	}

	@Test
	void guardsTheCurrentStageAndRenewsTheLeaseAfterTheDomainLock() {
		ClaimedQuestionTask claim = claim();
		Duration extension = Duration.ofMinutes(2);
		when(repository.lockCurrentFence(claim))
			.thenReturn(Optional.of(new LockedQuestionCheckpoint(false)));
		when(repository.lockActiveQuestion(claim.questionId())).thenReturn(true);
		when(repository.renewLeaseAtStage(
			claim,
			QuestionTaskStage.GENERATING,
			extension
		)).thenReturn(true);

		QuestionCheckpointResult result = service.guardCurrentStage(
			claim,
			QuestionTaskStage.GENERATING,
			extension
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
		InOrder order = inOrder(repository);
		order.verify(repository).lockCurrentFence(claim);
		order.verify(repository).lockActiveQuestion(claim.questionId());
		order.verify(repository).renewLeaseAtStage(
			claim,
			QuestionTaskStage.GENERATING,
			extension
		);
		order.verifyNoMoreInteractions();
	}

	@Test
	void guardCancellationUsesTheCurrentFenceWithoutRenewingTheLease() {
		ClaimedQuestionTask claim = claim();
		when(repository.lockCurrentFence(claim))
			.thenReturn(Optional.of(new LockedQuestionCheckpoint(true)));
		when(repository.cancelCurrentFence(claim)).thenReturn(true);

		QuestionCheckpointResult result = service.guardCurrentStage(
			claim,
			QuestionTaskStage.GENERATING,
			Duration.ofMinutes(2)
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.CANCELLED);
		InOrder order = inOrder(repository);
		order.verify(repository).lockCurrentFence(claim);
		order.verify(repository).cancelCurrentFence(claim);
		order.verifyNoMoreInteractions();
		verify(repository, never()).renewLeaseAtStage(
			claim,
			QuestionTaskStage.GENERATING,
			Duration.ofMinutes(2)
		);
	}

	@Test
	void cancellationRequestUsesTheSameFenceAndSkipsDomainLock() {
		ClaimedQuestionTask claim = claim();
		when(repository.lockCurrentFence(claim))
			.thenReturn(Optional.of(new LockedQuestionCheckpoint(true)));
		when(repository.cancelCurrentFence(claim)).thenReturn(true);

		QuestionCheckpointResult result = service.saveAnalysis(
			claim,
			analysis(),
			Duration.ofMinutes(2)
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.CANCELLED);
		InOrder order = inOrder(repository);
		order.verify(repository).lockCurrentFence(claim);
		order.verify(repository).cancelCurrentFence(claim);
		order.verifyNoMoreInteractions();
		verify(repository, never()).lockActiveQuestion(claim.questionId());
	}

	@Test
	void missingFenceAndUpdateZeroAreTypedStaleResults() {
		ClaimedQuestionTask claim = claim();
		when(repository.lockCurrentFence(claim)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.saveAnalysis(
			claim,
			analysis(),
			Duration.ofMinutes(2)
		)).isInstanceOf(StaleQuestionCheckpointException.class);

		when(repository.lockCurrentFence(claim))
			.thenReturn(Optional.of(new LockedQuestionCheckpoint(false)));
		when(repository.lockActiveQuestion(claim.questionId())).thenReturn(true);
		when(repository.saveAnalysis(claim, analysis(), Duration.ofMinutes(2))).thenReturn(false);
		assertThatThrownBy(() -> service.saveAnalysis(
			claim,
			analysis(),
			Duration.ofMinutes(2)
		)).isInstanceOf(StaleQuestionCheckpointException.class);
	}

	@Test
	void rejectsUnsupportedStageTransitionBeforeAnyDatabaseQuery() {
		ClaimedQuestionTask claim = claim();

		assertThatThrownBy(() -> service.guardAndAdvance(
			claim,
			QuestionTaskStage.RETRIEVING,
			QuestionTaskStage.VALIDATING,
			Duration.ofMinutes(2)
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.guardAndAdvance(
			claim,
			QuestionTaskStage.PERSISTING,
			QuestionTaskStage.VALIDATING,
			Duration.ofMinutes(2)
		)).isInstanceOf(IllegalArgumentException.class);

		verifyNoInteractions(repository);
	}

	@Test
	void allowsRetrievingToPersistingForAWebDisabledInsufficientResult() {
		ClaimedQuestionTask claim = claim();
		Duration extension = Duration.ofMinutes(2);
		when(repository.lockCurrentFence(claim))
			.thenReturn(Optional.of(new LockedQuestionCheckpoint(false)));
		when(repository.lockActiveQuestion(claim.questionId())).thenReturn(true);
		when(repository.advanceStage(
			claim,
			QuestionTaskStage.RETRIEVING,
			QuestionTaskStage.PERSISTING,
			extension
		)).thenReturn(true);

		QuestionCheckpointResult result = service.guardAndAdvance(
			claim,
			QuestionTaskStage.RETRIEVING,
			QuestionTaskStage.PERSISTING,
			extension
		);

		assertThat(result).isEqualTo(QuestionCheckpointResult.APPLIED);
		InOrder order = inOrder(repository);
		order.verify(repository).lockCurrentFence(claim);
		order.verify(repository).lockActiveQuestion(claim.questionId());
		order.verify(repository).advanceStage(
			claim,
			QuestionTaskStage.RETRIEVING,
			QuestionTaskStage.PERSISTING,
			extension
		);
		order.verifyNoMoreInteractions();
	}

	private ClaimedQuestionTask claim() {
		return new ClaimedQuestionTask(
			42L,
			"worker-a",
			UUID.fromString("11111111-1111-1111-1111-111111111111"),
			OffsetDateTime.now().plusMinutes(2),
			1
		);
	}

	private QueryAnalysis analysis() {
		return new QueryAnalysis(
			GeoScope.regional,
			new BigDecimal("0.82"),
			RegionContext.korea("서울특별시", "종로구", null, null),
			"transport",
			false,
			List.of("버스"),
			List.of("한국 버스 승하차"),
			"query-analysis-v1"
		);
	}
}
