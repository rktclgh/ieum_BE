package shinhan.fibri.ieum.main.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryRequest;
import shinhan.fibri.ieum.main.inquiry.dto.CreateInquiryResponse;
import shinhan.fibri.ieum.main.inquiry.dto.InquiryListResponse;
import shinhan.fibri.ieum.main.inquiry.repository.InquiryRepository;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

class InquiryServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final InquiryRepository inquiryRepository = mock(InquiryRepository.class);
	private final InquiryService service = new InquiryService(userRepository, inquiryRepository);

	@Test
	void createsInquiryWithTrimmedProvidedTitle() {
		prepareExistingUser();
		prepareSave(90L);

		CreateInquiryResponse response = service.create(principal(UserStatus.active), new CreateInquiryRequest("  제목  ", "문의 내용"));

		assertThat(response.inquiryId()).isEqualTo(90L);
		assertThat(savedInquiry().getTitle()).isEqualTo("제목");
	}

	@Test
	void derivesTitleFromNormalizedContentWhenTitleIsBlank() {
		prepareExistingUser();
		prepareSave(91L);

		service.create(principal(UserStatus.active), new CreateInquiryRequest(" ", "  첫 줄\n두 번째 줄  "));

		assertThat(savedInquiry().getTitle()).isEqualTo("첫 줄 두 번째 줄");
	}

	@Test
	void truncatesDerivedTitleAtFiftyCharacters() {
		prepareExistingUser();
		prepareSave(92L);
		String content = "가".repeat(51);

		service.create(principal(UserStatus.active), new CreateInquiryRequest(null, content));

		assertThat(savedInquiry().getTitle()).isEqualTo("가".repeat(50));
	}

	@Test
	void truncatesDerivedTitleAtFiftyCodePointsWithoutSplittingEmoji() {
		prepareExistingUser();
		prepareSave(94L);
		String emoji = "\uD83D\uDE00";
		String content = "가".repeat(49) + emoji + "끝";

		service.create(principal(UserStatus.active), new CreateInquiryRequest(null, content));

		Inquiry savedInquiry = savedInquiry();
		assertThat(savedInquiry.getTitle()).isEqualTo("가".repeat(49) + emoji);
		assertThat(savedInquiry.getTitle().codePointCount(0, savedInquiry.getTitle().length())).isEqualTo(50);
	}

	@Test
	void rejectsInquiryCreationForMissingUser() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(principal(UserStatus.active), new CreateInquiryRequest(null, "문의 내용")))
			.isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void rejectsNullContentBeforeAccessingRepositories() {
		assertThatThrownBy(() -> service.create(principal(UserStatus.active), new CreateInquiryRequest(null, null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("content must not be blank");

		verifyNoInteractions(userRepository, inquiryRepository);
	}

	@Test
	void allowsSuspendedUserToCreateInquiry() {
		prepareExistingUser();
		prepareSave(93L);

		CreateInquiryResponse response = service.create(principal(UserStatus.suspended), new CreateInquiryRequest(null, "제재 이의 문의"));

		assertThat(response.inquiryId()).isEqualTo(93L);
		verify(inquiryRepository).save(any(Inquiry.class));
	}

	@Test
	void listsOnlyTheCurrentUsersInquiriesAsResponseItems() {
		Inquiry inquiry = Inquiry.create(42L, "문의 제목", "문의 내용");
		ReflectionTestUtils.setField(inquiry, "id", 100L);
		when(inquiryRepository.findByUserIdOrderByCreatedAtDescIdDesc(42L)).thenReturn(List.of(inquiry));

		InquiryListResponse response = service.listMine(42L);

		assertThat(response.items()).singleElement().satisfies(item -> {
			assertThat(item.inquiryId()).isEqualTo(100L);
			assertThat(item.title()).isEqualTo("문의 제목");
			assertThat(item.content()).isEqualTo("문의 내용");
			assertThat(item.status()).isEqualTo(shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus.pending);
			assertThat(item.answer()).isNull();
			assertThat(item.answeredAt()).isNull();
		});
	}

	private void prepareExistingUser() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(mock(User.class)));
	}

	private void prepareSave(Long inquiryId) {
		when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> {
			Inquiry inquiry = invocation.getArgument(0);
			ReflectionTestUtils.setField(inquiry, "id", inquiryId);
			return inquiry;
		});
	}

	private Inquiry savedInquiry() {
		ArgumentCaptor<Inquiry> inquiryCaptor = ArgumentCaptor.forClass(Inquiry.class);
		verify(inquiryRepository).save(inquiryCaptor.capture());
		return inquiryCaptor.getValue();
	}

	private AuthenticatedUser principal(UserStatus status) {
		return new AuthenticatedUser(42L, "user@example.com", UserRole.user, status);
	}
}
