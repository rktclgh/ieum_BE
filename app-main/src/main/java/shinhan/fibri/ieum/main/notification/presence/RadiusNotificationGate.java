package shinhan.fibri.ieum.main.notification.presence;

public interface RadiusNotificationGate {

	boolean tryAcquire(NotificationCategory category, Long refId, String geoHash);
}
