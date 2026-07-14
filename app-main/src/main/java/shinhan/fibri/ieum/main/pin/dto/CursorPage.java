package shinhan.fibri.ieum.main.pin.dto;

import java.util.List;

public record CursorPage<T>(
	List<T> items,
	String nextCursor
) {
}
