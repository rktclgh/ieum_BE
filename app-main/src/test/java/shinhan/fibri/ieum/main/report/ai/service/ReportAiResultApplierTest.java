package shinhan.fibri.ieum.main.report.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;
import shinhan.fibri.ieum.main.report.repository.ReportAiReviewResult;
import shinhan.fibri.ieum.main.report.repository.ReportAiWorkRepository;

class ReportAiResultApplierTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-14T12:00:00+09:00");
	private final ReportAiWorkRepository workRepository = mock(ReportAiWorkRepository.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserSanctionRepository sanctionRepository = mock(UserSanctionRepository.class);
	private final ReportAiReviewResultMapper mapper = mock(ReportAiReviewResultMapper.class);
	private final ReportAiPostCommitActions postCommitActions = mock(ReportAiPostCommitActions.class);
	private final Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.ofHours(9));
	private ReportAiResultApplierImpl applier;

	@BeforeEach
	void setUp() {
		applier = new ReportAiResultApplierImpl(
			workRepository, userRepository, sanctionRepository, mapper, postCommitActions, clock
		);
	}

	@Test
	void appendsAHighSeveritySentenceAfterTheCurrentSentenceAndSuspendsAfterFencing() {
		ClaimedReport claimed = claimed();
		ReportReviewResponse response = mock(ReportReviewResponse.class);
		ReportAiReviewResult result = result("suspend");
		when(mapper.map(response, NOW)).thenReturn(new MappedReportAiReview(result, Duration.ofHours(72)));
		User target = mock(User.class);
		when(target.getRole()).thenReturn(UserRole.user);
		when(userRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(target));
		UserSanction current = mock(UserSanction.class);
		when(current.getType()).thenReturn(SanctionType.temporary);
		when(current.getEndsAt()).thenReturn(NOW.plusHours(24));
		when(sanctionRepository.findEffectiveSanctionsForUpdate(30L, NOW)).thenReturn(List.of(current));
		when(workRepository.markCompleted(900L, claimed.attemptId(), result)).thenReturn(true);

		ReportAiApplyOutcome outcome = applier.apply(claimed, response);

		assertThat(outcome).isEqualTo(ReportAiApplyOutcome.completed("suspend", true));
		ArgumentCaptor<UserSanction> sanction = ArgumentCaptor.forClass(UserSanction.class);
		verify(sanctionRepository).saveAndFlush(sanction.capture());
		assertThat(sanction.getValue().getReportId()).isEqualTo(900L);
		assertThat(sanction.getValue().getStartsAt()).isEqualTo(NOW.plusHours(24));
		assertThat(sanction.getValue().getEndsAt()).isEqualTo(NOW.plusHours(96));
		verify(target).suspend();
		verify(postCommitActions).schedule(30L);
		var order = inOrder(userRepository, sanctionRepository, workRepository);
		order.verify(userRepository).findByIdForUpdate(30L);
		order.verify(sanctionRepository).findEffectiveSanctionsForUpdate(30L, NOW);
		order.verify(workRepository).markCompleted(900L, claimed.attemptId(), result);
	}

	@Test
	void discardsAStaleSuspendBeforeCreatingTheSanction() {
		ClaimedReport claimed = claimed();
		ReportReviewResponse response = mock(ReportReviewResponse.class);
		ReportAiReviewResult result = result("suspend");
		when(mapper.map(response, NOW)).thenReturn(new MappedReportAiReview(result, Duration.ofHours(72)));
		User target = mock(User.class);
		when(target.getRole()).thenReturn(UserRole.user);
		when(userRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(target));
		when(sanctionRepository.findEffectiveSanctionsForUpdate(30L, NOW)).thenReturn(List.of());
		when(workRepository.markCompleted(900L, claimed.attemptId(), result)).thenReturn(false);

		assertThat(applier.apply(claimed, response)).isEqualTo(ReportAiApplyOutcome.stale("suspend"));

		verify(sanctionRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
		verify(target, never()).suspend();
		verify(postCommitActions, never()).schedule(30L);
	}

	@Test
	void completesHoldWithoutLockingOrChangingTheUser() {
		ClaimedReport claimed = claimed();
		ReportReviewResponse response = mock(ReportReviewResponse.class);
		ReportAiReviewResult result = result("hold");
		when(mapper.map(response, NOW)).thenReturn(new MappedReportAiReview(result, null));
		when(workRepository.markCompleted(900L, claimed.attemptId(), result)).thenReturn(true);

		assertThat(applier.apply(claimed, response)).isEqualTo(ReportAiApplyOutcome.completed("hold", false));

		verify(userRepository, never()).findByIdForUpdate(30L);
		verify(sanctionRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
		verify(postCommitActions, never()).schedule(30L);
	}

	@Test
	void doesNotAppendARedundantTemporarySentenceAfterAnEffectivePermanentSanction() {
		ClaimedReport claimed = claimed();
		ReportReviewResponse response = mock(ReportReviewResponse.class);
		ReportAiReviewResult result = result("suspend");
		when(mapper.map(response, NOW)).thenReturn(new MappedReportAiReview(result, Duration.ofDays(7)));
		User target = mock(User.class);
		when(target.getRole()).thenReturn(UserRole.user);
		when(userRepository.findByIdForUpdate(30L)).thenReturn(Optional.of(target));
		UserSanction permanent = mock(UserSanction.class);
		when(permanent.getType()).thenReturn(SanctionType.permanent);
		when(sanctionRepository.findEffectiveSanctionsForUpdate(30L, NOW)).thenReturn(List.of(permanent));
		when(workRepository.markCompleted(900L, claimed.attemptId(), result)).thenReturn(true);

		assertThat(applier.apply(claimed, response))
			.isEqualTo(ReportAiApplyOutcome.completed("suspend", false));
		verify(sanctionRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
		verify(postCommitActions, never()).schedule(30L);
	}

	private ClaimedReport claimed() {
		return new ClaimedReport(
			900L, 3L, 10L, 30L, ReportReason.abuse, "detail", "{}", "a".repeat(64),
			UUID.fromString("22222222-2222-2222-2222-222222222222"), 1, NOW.plusMinutes(2)
		);
	}

	private ReportAiReviewResult result(String decision) {
		return new ReportAiReviewResult(
			decision,
			"suspend".equals(decision) ? "temporary_suspend" : "hold",
			new BigDecimal("0.9400"),
			"reason",
			"model-v1",
			"report-review-v1",
			"a".repeat(64),
			NOW,
			"{}"
		);
	}
}
