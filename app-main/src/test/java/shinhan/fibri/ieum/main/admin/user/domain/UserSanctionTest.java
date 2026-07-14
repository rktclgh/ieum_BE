package shinhan.fibri.ieum.main.admin.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class UserSanctionTest {

	@Test
	void temporaryCreatesActiveSanctionWithEndsAt() {
		OffsetDateTime endsAt = OffsetDateTime.parse("2026-07-11T00:00:00+09:00");

		UserSanction sanction = UserSanction.temporary(10L, "abuse", 1L, endsAt);

		assertThat(sanction.getUserId()).isEqualTo(10L);
		assertThat(sanction.getType()).isEqualTo(SanctionType.temporary);
		assertThat(sanction.getReason()).isEqualTo("abuse");
		assertThat(sanction.getCreatedBy()).isEqualTo(1L);
		assertThat(sanction.getEndsAt()).isEqualTo(endsAt);
		assertThat(sanction.getCreatedAt()).isNotNull();
		assertThat(sanction.isActive()).isTrue();
	}

	@Test
	void temporaryRequiresEndsAt() {
		assertThatThrownBy(() -> UserSanction.temporary(10L, "abuse", 1L, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("endsAt is required for temporary sanction");
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
		assertThatThrownBy(() -> sanction.release(releasedAt.plusMinutes(1), 3L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("sanction already released");
	}
}
