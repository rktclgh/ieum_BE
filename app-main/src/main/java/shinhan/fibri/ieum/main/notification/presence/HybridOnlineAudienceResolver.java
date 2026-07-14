package shinhan.fibri.ieum.main.notification.presence;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HybridOnlineAudienceResolver implements OnlineAudienceResolver {
	private static final double EARTH_RADIUS_METERS = 6_371_000d;
	private static final int NEIGHBOR_RINGS = 3;
	private final PresenceRegistry presenceRegistry;

	public HybridOnlineAudienceResolver(PresenceRegistry presenceRegistry) {
		this.presenceRegistry = presenceRegistry;
	}

	@Override
	public List<Long> resolve(double latitude, double longitude, NotificationCategory category, Long authorId, Set<Long> blockedUserIds) {
		Set<Long> candidateIds = inKoreaFastPath(latitude, longitude)
			? presenceRegistry.nearbyUserIds(latitude, longitude, NEIGHBOR_RINGS)
			: presenceRegistry.allUserIds();
		return candidateIds.stream()
			.flatMap(userId -> presenceRegistry.findByUserId(userId)
				.map(snapshot -> java.util.Map.entry(userId, snapshot))
				.stream())
			.filter(entry -> !entry.getKey().equals(authorId) && !blockedUserIds.contains(entry.getKey()))
			.filter(entry -> enabled(entry.getValue(), category))
			.filter(entry -> entry.getValue().latitude() != null && distance(latitude, longitude, entry.getValue()) <= entry.getValue().notifyRadiusKm() * 1000d)
			.map(java.util.Map.Entry::getKey).toList();
	}

	private boolean inKoreaFastPath(double latitude, double longitude) {
		return latitude >= 32 && latitude <= 40 && longitude >= 122 && longitude <= 134;
	}

	private boolean enabled(PresenceSnapshot snapshot, NotificationCategory category) {
		if (!snapshot.notifyAllEnabled()) {
			return false;
		}
		return switch (category) {
			case question -> snapshot.notifyQuestion();
			case meeting -> snapshot.notifyMeeting();
		};
	}

	private double distance(double latitude, double longitude, PresenceSnapshot snapshot) {
		double dLat = Math.toRadians(snapshot.latitude() - latitude), dLon = Math.toRadians(snapshot.longitude() - longitude);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(snapshot.latitude())) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 2 * EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}
}
