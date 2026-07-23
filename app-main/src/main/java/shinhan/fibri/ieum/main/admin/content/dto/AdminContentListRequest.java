package shinhan.fibri.ieum.main.admin.content.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record AdminContentListRequest(
	String cursor,
	@Min(1) @Max(100) Integer size
) {
}
