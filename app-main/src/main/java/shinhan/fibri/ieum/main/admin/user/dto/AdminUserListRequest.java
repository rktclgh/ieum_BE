package shinhan.fibri.ieum.main.admin.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;

public record AdminUserListRequest(
	UserStatus status,
	@Size(max = 100)
	String q,
	String cursor,
	@Min(1)
	@Max(50)
	Integer size
) {
}
