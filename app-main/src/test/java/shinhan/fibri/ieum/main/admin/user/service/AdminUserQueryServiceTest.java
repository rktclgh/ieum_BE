package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserDetailResponse;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserListRequest;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserQueryRepository;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserQueryRepository.AdminUserRow;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;

class AdminUserQueryServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserSanctionRepository sanctionRepository = mock(UserSanctionRepository.class);
	private final AdminUserQueryRepository adminUserQueryRepository = mock(AdminUserQueryRepository.class);
	private final AdminUserQueryService service =
		new AdminUserQueryService(userRepository, sanctionRepository, adminUserQueryRepository);

	@Test
	void getUsersDefaultsSizeToTwentyAndRequestsOneExtraRow() {
		when(adminUserQueryRepository.findUsers(isNull(), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		service.getUsers(new AdminUserListRequest(null, null, null, null));

		org.mockito.Mockito.verify(adminUserQueryRepository).findUsers(isNull(), isNull(), isNull(), eq(21));
	}

	@Test
	void getUsersReturnsNextCursorWhenMoreRowsExist() {
		when(adminUserQueryRepository.findUsers(any(), any(), any(), eq(2)))
			.thenReturn(List.of(row(2L), row(1L)));

		var page = service.getUsers(new AdminUserListRequest(null, null, null, 1));

		assertThat(page.items()).hasSize(1);
		assertThat(page.items().get(0).userId()).isEqualTo(2L);
		assertThat(page.nextCursor()).isEqualTo(AdminUserCursor.encode(2L));
	}

	@Test
	void getUsersReturnsNullNextCursorWhenNoMoreRows() {
		when(adminUserQueryRepository.findUsers(any(), any(), any(), eq(2)))
			.thenReturn(List.of(row(1L)));

		var page = service.getUsers(new AdminUserListRequest(null, null, null, 1));

		assertThat(page.items()).hasSize(1);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void getUserThrowsWhenUserNotFound() {
		when(userRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getUser(10L)).isInstanceOf(AdminUserNotFoundException.class);
	}

	@Test
	void getUserUsesUsersAcceptedCountColumnForActivity() {
		User user = User.createEmailUser(
			"user@example.com", "hash", "user", LocalDate.of(2000, 1, 1), GenderType.female, "KR"
		);
		user.recordAcceptedAnswer();
		user.recordAcceptedAnswer();
		when(userRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(user));
		when(adminUserQueryRepository.findReports(eq(10L), anyInt())).thenReturn(List.of());
		when(sanctionRepository.findByUserIdOrderByCreatedAtDesc(10L)).thenReturn(List.of());
		when(adminUserQueryRepository.countQuestions(10L)).thenReturn(3);
		when(adminUserQueryRepository.countAnswers(10L)).thenReturn(5);
		when(adminUserQueryRepository.countReports(10L)).thenReturn(1);

		AdminUserDetailResponse detail = service.getUser(10L);

		assertThat(detail.activity().acceptedCount()).isEqualTo(user.getAcceptedCount());
		assertThat(detail.activity().questionCount()).isEqualTo(3);
		assertThat(detail.activity().answerCount()).isEqualTo(5);
		assertThat(detail.activity().reportedCount()).isEqualTo(1);
	}

	private static AdminUserRow row(Long userId) {
		return new AdminUserRow(
			userId, "user@example.com", "user", "user", "active", "bronze", "email", OffsetDateTime.now()
		);
	}
}
