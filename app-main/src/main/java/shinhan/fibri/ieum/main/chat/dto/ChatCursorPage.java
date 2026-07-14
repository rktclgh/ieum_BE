package shinhan.fibri.ieum.main.chat.dto;

import java.util.List;

public record ChatCursorPage<T>(
	List<T> items,
	String nextCursor
) {
}
