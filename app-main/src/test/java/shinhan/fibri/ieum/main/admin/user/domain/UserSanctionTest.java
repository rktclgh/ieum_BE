package shinhan.fibri.ieum.main.admin.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class UserSanctionTest {

	@Test
	void temporaryCreatesActiveSanctionWithEndsAt() {
		OffsetDateTime endsAt = OffsetDateTime.now().plusDays(1);

		UserSanction sanction = UserSanction.temporary(10L, "abuse", 1L, endsAt);

		assertThat(sanction.getUserId()).isEqualTo(10L);
		assertThat(sanction.getType()).isEqualTo(SanctionType.temporary);
		assertThat(sanction.getReason()).isEqualTo("abuse");
		assertThat(sanction.getCreatedBy()).isEqualTo(1L);
		assertThat(sanction.getEndsAt()).isEqualTo(endsAt);
		assertThat(sanction.getCreatedAt()).isNotNull();
		assertThat(sanction.getStartsAt()).isNotNull();
		assertThat(sanction.getDurationMinutes()).isPositive();
		assertThat(sanction.getDecisionSource()).isEqualTo(SanctionDecisionSource.admin);
		assertThat(sanction.getReviewStatus()).isEqualTo(SanctionReviewStatus.not_required);
		assertThat(sanction.isActive()).isTrue();
	}

	@Test
	void aiTemporaryCreatesPendingReviewSanctionLinkedToReport() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-09T12:00:00+09:00");
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-10T00:00:00+09:00");
		OffsetDateTime endsAt = startsAt.plusMinutes(90);

		UserSanction sanction = UserSanction.aiTemporary(10L, 20L, "ai abuse", createdAt, startsAt, endsAt);

		assertThat(sanction.getReportId()).isEqualTo(20L);
		assertThat(sanction.getDecisionSource()).isEqualTo(SanctionDecisionSource.ai_recommendation);
		assertThat(sanction.getReviewStatus()).isEqualTo(SanctionReviewStatus.pending_review);
		assertThat(sanction.getCreatedBy()).isNull();
		assertThat(sanction.getCreatedAt()).isEqualTo(createdAt);
		assertThat(sanction.getCreatedAt()).isBefore(sanction.getStartsAt());
		assertThat(sanction.getDurationMinutes()).isEqualTo(90);
	}

	@Test
	void temporaryRequiresEndsAt() {
		assertThatThrownBy(() -> UserSanction.temporary(10L, "abuse", 1L, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endsAt is required for temporary sanction");
	}

	@Test
	void aiTemporaryRequiresEndsAtAfterStartsAt() {
		OffsetDateTime startsAt = OffsetDateTime.parse("2026-07-15T12:00:00+09:00");

		assertThatThrownBy(() -> UserSanction.aiTemporary(
			10L, 20L, "ai abuse", startsAt.minusMinutes(1), startsAt, startsAt
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endsAt must be after startsAt");
		assertThatThrownBy(() -> UserSanction.aiTemporary(
			10L, 20L, "ai abuse", startsAt.minusMinutes(1), startsAt, startsAt.minusSeconds(1)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endsAt must be after startsAt");
	}

	@Test
	void permanentCreatesActiveSanctionWithoutEndsAt() {
		UserSanction sanction = UserSanction.permanent(10L, "abuse", 1L);

		assertThat(sanction.getType()).isEqualTo(SanctionType.permanent);
		assertThat(sanction.getEndsAt()).isNull();
		assertThat(sanction.isActive()).isTrue();
	}

	@Test
	void releaseRecordsReleaseInfoAndRejectsDoubleRelease() {
		UserSanction sanction = UserSanction.permanent(10L, "abuse", 1L);
		OffsetDateTime releasedAt = OffsetDateTime.parse("2026-07-10T10:00:00+09:00");

		sanction.release(releasedAt, 2L);

		assertThat(sanction.isActive()).isFalse();
		assertThat(sanction.getReleasedAt()).isEqualTo(releasedAt);
		assertThat(sanction.getReleasedBy()).isEqualTo(2L);
		assertThat(sanction.getRevokedAt()).isEqualTo(releasedAt);
		assertThat(sanction.getRevokedBy()).isEqualTo(2L);
		assertThatThrownBy(() -> sanction.release(releasedAt.plusMinutes(1), 3L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("sanction already released");
	}
}
