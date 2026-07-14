package shinhan.fibri.ieum.main.notification.dto;

import java.util.List;

public record NotificationListResponse(
	List<NotificationItem> items,
	String nextCursor,
	long unreadCount
) {
}
