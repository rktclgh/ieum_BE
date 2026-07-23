package shinhan.fibri.ieum.main.admin.content.dto;

import java.util.List;

public record AdminContentListResponse(
	List<AdminContentListItem> items,
	String nextCursor
) {
	public AdminContentListResponse {
		items = List.copyOf(items);
	}
}
