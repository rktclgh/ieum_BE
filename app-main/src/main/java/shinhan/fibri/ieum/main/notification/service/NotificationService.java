package shinhan.fibri.ieum.main.notification.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.dto.NotificationItem;
import shinhan.fibri.ieum.main.notification.dto.NotificationListResponse;
import shinhan.fibri.ieum.main.notification.dto.NotificationReadAllResponse;
import shinhan.fibri.ieum.main.notification.exception.NotificationNotFoundException;
import shinhan.fibri.ieum.main.notification.repository.NotificationRepository;

@Service
@RequiredArgsConstructor
public class NotificationService {

	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 50;

	private final NotificationRepository notificationRepository;

	@Transactional(readOnly = true)
	public NotificationListResponse list(Long userId, String cursor, Integer size) {
		int pageSize = normalizePageSize(size);
		NotificationCursor decodedCursor = NotificationCursor.decode(cursor);
		List<Notification> notifications = decodedCursor == null
			? notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, pageSize + 1))
			: notificationRepository.findPage(
				userId,
				decodedCursor.createdAt(),
				decodedCursor.notificationId(),
				PageRequest.of(0, pageSize + 1)
			);
		boolean hasNext = notifications.size() > pageSize;
		List<Notification> pageItems = notifications.stream().limit(pageSize).toList();
		String nextCursor = hasNext
			? NotificationCursor.encode(pageItems.getLast().getCreatedAt(), pageItems.getLast().getId())
			: null;

		return new NotificationListResponse(
			pageItems.stream().map(NotificationItem::from).toList(),
			nextCursor,
			notificationRepository.countUnreadByUserId(userId)
		);
	}

	@Transactional
	public void markRead(Long userId, Long notificationId) {
		if (notificationRepository.markReadByIdAndUserId(notificationId, userId) == 0) {
			throw new NotificationNotFoundException();
		}
	}

	@Transactional
	public NotificationReadAllResponse markAllRead(Long userId) {
		return new NotificationReadAllResponse(notificationRepository.markAllRead(userId));
	}

	@Transactional
	public void delete(Long userId, Long notificationId) {
		if (notificationRepository.deleteByIdAndUserId(notificationId, userId) == 0) {
			throw new NotificationNotFoundException();
		}
	}

	private int normalizePageSize(Integer size) {
		if (size == null) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.clamp(size, 1, MAX_PAGE_SIZE);
	}
}
