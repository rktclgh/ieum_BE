package shinhan.fibri.ieum.main.admin.user.dto;

import java.util.List;

public record CursorPage<T>(
	List<T> items,
	String nextCursor
) {
}
