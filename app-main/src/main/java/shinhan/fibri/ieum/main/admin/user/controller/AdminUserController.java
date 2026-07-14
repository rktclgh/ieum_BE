package shinhan.fibri.ieum.main.admin.user.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserDetailResponse;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserItem;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserListRequest;
import shinhan.fibri.ieum.main.admin.user.dto.ChangeUserRoleRequest;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionRequest;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionResponse;
import shinhan.fibri.ieum.main.admin.user.dto.CursorPage;
import shinhan.fibri.ieum.main.admin.user.service.AdminSanctionService;
import shinhan.fibri.ieum.main.admin.user.service.AdminUserQueryService;
import shinhan.fibri.ieum.main.admin.user.service.AdminUserRoleService;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

	private final AdminUserQueryService adminUserQueryService;
	private final AdminSanctionService adminSanctionService;
	private final AdminUserRoleService adminUserRoleService;

	@GetMapping
	public ResponseEntity<CursorPage<AdminUserItem>> getUsers(
		@Valid @ModelAttribute AdminUserListRequest request
	) {
		return ResponseEntity.ok(adminUserQueryService.getUsers(request));
	}

	@GetMapping("/{userId}")
	public ResponseEntity<AdminUserDetailResponse> getUser(@PathVariable Long userId) {
		return ResponseEntity.ok(adminUserQueryService.getUser(userId));
	}

	@PostMapping("/{userId}/sanctions")
	public ResponseEntity<CreateSanctionResponse> sanction(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId,
		@Valid @RequestBody CreateSanctionRequest request
	) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(adminSanctionService.sanction(principal, userId, request));
	}

	@PostMapping("/{userId}/activate")
	public ResponseEntity<Void> activate(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId
	) {
		adminSanctionService.activate(principal, userId);
		return ResponseEntity.noContent().build();
	}

	@PatchMapping("/{userId}/role")
	public ResponseEntity<Void> changeRole(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@PathVariable Long userId,
		@Valid @RequestBody ChangeUserRoleRequest request
	) {
		adminUserRoleService.changeRole(principal, userId, request.role());
		return ResponseEntity.noContent().build();
	}
}
