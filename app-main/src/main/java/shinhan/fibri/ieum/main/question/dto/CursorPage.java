package shinhan.fibri.ieum.main.question.dto;

import java.util.List;

public record CursorPage<T>(
	List<T> items,
	String nextCursor
) {
}
