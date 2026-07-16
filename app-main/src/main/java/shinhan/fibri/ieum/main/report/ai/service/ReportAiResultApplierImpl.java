package shinhan.fibri.ieum.main.report.ai.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;
import shinhan.fibri.ieum.main.report.repository.ReportAiWorkRepository;
import shinhan.fibri.ieum.main.mail.UserSuspensionEventPublisher;

@Service
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class ReportAiResultApplierImpl implements ReportAiResultApplier {

	private final ReportAiWorkRepository workRepository;
	private final UserRepository userRepository;
	private final UserSanctionRepository sanctionRepository;
	private final ReportAiReviewResultMapper mapper;
	private final ReportAiPostCommitActions postCommitActions;
	private final UserSuspensionEventPublisher suspensionEventPublisher;
	private final Clock clock;

	public ReportAiResultApplierImpl(
		ReportAiWorkRepository workRepository,
		UserRepository userRepository,
		UserSanctionRepository sanctionRepository,
		ReportAiReviewResultMapper mapper,
		ReportAiPostCommitActions postCommitActions,
		UserSuspensionEventPublisher suspensionEventPublisher,
		Clock clock
	) {
		this.workRepository = Objects.requireNonNull(workRepository, "workRepository must not be null");
		this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
		this.sanctionRepository = Objects.requireNonNull(sanctionRepository, "sanctionRepository must not be null");
		this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
		this.postCommitActions = Objects.requireNonNull(postCommitActions, "postCommitActions must not be null");
		this.suspensionEventPublisher = Objects.requireNonNull(
			suspensionEventPublisher,
			"suspensionEventPublisher must not be null"
		);
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	@Transactional
	public ReportAiApplyOutcome apply(ClaimedReport claimed, ReportReviewResponse response) {
		Objects.requireNonNull(claimed, "claimed must not be null");
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), clock.getZone());
		MappedReportAiReview mapped = mapper.map(response, now);
		if (!mapped.requiresAutomaticSanction()) {
			return completeWithoutSanction(claimed, mapped);
		}

		User target = userRepository.findByIdForUpdate(claimed.reportedUserId())
			.orElseThrow(() -> new ReportAiPermanentException("REPORT_TARGET_USER_MISSING"));
		List<UserSanction> effective = sanctionRepository.findEffectiveSanctionsForUpdate(
			claimed.reportedUserId(), now
		);
		boolean transitioned = workRepository.markCompleted(
			claimed.reportId(), claimed.attemptId(), mapped.result()
		);
		if (!transitioned) {
			return ReportAiApplyOutcome.stale(mapped.result().decision());
		}
		if (target.getRole() == UserRole.admin || hasPermanent(effective)) {
			return ReportAiApplyOutcome.completed(mapped.result().decision(), false);
		}

		OffsetDateTime startsAt = effective.stream()
			.filter(sanction -> sanction.getType() == SanctionType.temporary)
			.map(UserSanction::getEndsAt)
			.filter(Objects::nonNull)
			.max(Comparator.naturalOrder())
			.filter(end -> end.isAfter(now))
			.orElse(now);
		OffsetDateTime endsAt = startsAt.plus(mapped.automaticSanctionDuration());
		UserSanction sanction = UserSanction.aiTemporary(
			claimed.reportedUserId(), claimed.reportId(), mapped.result().reason(), now, startsAt, endsAt
		);
		boolean newlySuspended = target.getStatus() != UserStatus.suspended;
		sanctionRepository.saveAndFlush(sanction);
		target.suspend();
		if (newlySuspended) {
			suspensionEventPublisher.publish(target, sanction);
		}
		postCommitActions.schedule(claimed.reportedUserId());
		return ReportAiApplyOutcome.completed(mapped.result().decision(), true);
	}

	private ReportAiApplyOutcome completeWithoutSanction(ClaimedReport claimed, MappedReportAiReview mapped) {
		boolean transitioned = workRepository.markCompleted(
			claimed.reportId(), claimed.attemptId(), mapped.result()
		);
		return transitioned
			? ReportAiApplyOutcome.completed(mapped.result().decision(), false)
			: ReportAiApplyOutcome.stale(mapped.result().decision());
	}

	private boolean hasPermanent(List<UserSanction> sanctions) {
		return sanctions.stream().anyMatch(sanction -> sanction.getType() == SanctionType.permanent);
	}
}
