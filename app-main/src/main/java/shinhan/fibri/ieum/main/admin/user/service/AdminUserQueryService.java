package shinhan.fibri.ieum.main.admin.user.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserGrade;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;
import shinhan.fibri.ieum.main.admin.user.dto.AdminReportItem;
import shinhan.fibri.ieum.main.admin.user.dto.AdminSanctionItem;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserActivity;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserDetailResponse;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserItem;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserListRequest;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserProfile;
import shinhan.fibri.ieum.main.admin.user.dto.CursorPage;
import shinhan.fibri.ieum.main.admin.user.exception.AdminUserNotFoundException;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserQueryRepository;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserQueryRepository.AdminReportRow;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserQueryRepository.AdminUserRow;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.main.report.domain.ReportStatus;

@Service
@RequiredArgsConstructor
public class AdminUserQueryService {

	private static final int DEFAULT_SIZE = 20;
	private static final int REPORT_LIMIT = 20;

	private final UserRepository userRepository;
	private final UserSanctionRepository userSanctionRepository;
	private final AdminUserQueryRepository adminUserQueryRepository;

	@Transactional(readOnly = true)
	public CursorPage<AdminUserItem> getUsers(AdminUserListRequest request) {
		int size = request.size() == null ? DEFAULT_SIZE : request.size();
		Long cursorId = AdminUserCursor.decode(request.cursor());
		String qLike = qLike(request.q());
		List<AdminUserRow> rows = adminUserQueryRepository.findUsers(
			statusValue(request.status()),
			qLike,
			cursorId,
			size + 1
		);
		boolean hasNext = rows.size() > size;
		List<AdminUserItem> items = rows.stream()
			.limit(size)
			.map(this::toItem)
			.toList();
		String nextCursor = hasNext ? AdminUserCursor.encode(items.getLast().userId()) : null;
		return new CursorPage<>(items, nextCursor);
	}

	@Transactional(readOnly = true)
	public AdminUserDetailResponse getUser(Long userId) {
		User user = userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(AdminUserNotFoundException::new);
		List<AdminReportItem> reports = adminUserQueryRepository.findReports(userId, REPORT_LIMIT)
			.stream()
			.map(this::toReportItem)
			.toList();
		List<AdminSanctionItem> sanctions = userSanctionRepository.findByUserIdOrderByCreatedAtDesc(userId)
			.stream()
			.map(this::toSanctionItem)
			.toList();
		AdminUserActivity activity = new AdminUserActivity(
			adminUserQueryRepository.countQuestions(userId),
			adminUserQueryRepository.countAnswers(userId),
			adminUserQueryRepository.countAcceptedAnswers(userId),
			adminUserQueryRepository.countReports(userId)
		);
		return new AdminUserDetailResponse(toProfile(user), activity, reports, sanctions);
	}

	private AdminUserItem toItem(AdminUserRow row) {
		return new AdminUserItem(
			row.userId(),
			row.email(),
			row.nickname(),
			UserRole.valueOf(row.role()),
			UserStatus.valueOf(row.status()),
			UserGrade.valueOf(row.grade()),
			AuthProvider.valueOf(row.provider()),
			row.lastActiveAt()
		);
	}

	private AdminUserProfile toProfile(User user) {
		return new AdminUserProfile(
			user.getId(),
			user.getEmail(),
			user.getNickname(),
			user.getRole(),
			user.getStatus(),
			user.getGrade(),
			user.getProvider(),
			user.getBirthDate(),
			user.getGender(),
			user.getNationality(),
			profileImageUrl(user.getProfileFileId()),
			user.getLastActiveAt()
		);
	}

	private AdminReportItem toReportItem(AdminReportRow row) {
		return new AdminReportItem(
			row.reportId(),
			ReportReason.valueOf(row.reason()),
			ReportStatus.valueOf(row.status()),
			row.reporterId(),
			row.reporterNickname(),
			row.messageId(),
			row.detail(),
			row.createdAt()
		);
	}

	private AdminSanctionItem toSanctionItem(UserSanction sanction) {
		return new AdminSanctionItem(
			sanction.getId(),
			SanctionType.valueOf(sanction.getType().name()),
			sanction.getReason(),
			sanction.getCreatedAt(),
			sanction.getCreatedBy(),
			sanction.getEndsAt(),
			sanction.getReleasedAt(),
			sanction.getReleasedBy()
		);
	}

	private String statusValue(UserStatus status) {
		return status == null ? null : status.name();
	}

	private String qLike(String q) {
		if (q == null || q.isBlank()) {
			return null;
		}
		return "%" + escapeLike(q.trim().toLowerCase()) + "%";
	}

	private String escapeLike(String value) {
		return value
			.replace("\\", "\\\\")
			.replace("%", "\\%")
			.replace("_", "\\_");
	}

	private String profileImageUrl(java.util.UUID profileFileId) {
		return profileFileId == null ? null : "/api/v1/files/%s".formatted(profileFileId);
	}
}
