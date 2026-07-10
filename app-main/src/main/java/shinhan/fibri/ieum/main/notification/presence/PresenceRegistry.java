package shinhan.fibri.ieum.main.notification.presence;

import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PresenceRegistry {

	private final PresenceSeedRepository seedRepository;
	private final ConcurrentHashMap<Long, PresenceSnapshot> snapshots = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Set<Long>> userIdsByGeoHash = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, String> geoHashByUserId = new ConcurrentHashMap<>();

	public PresenceRegistry(PresenceSeedRepository seedRepository) {
		this.seedRepository = seedRepository;
	}

	public void seedOnConnect(Long userId) {
		seedRepository.findSeedByUserId(userId).ifPresent(seed -> replace(userId, new PresenceSnapshot(
			seed.latitude(), seed.longitude(), seed.notifyAllEnabled(), seed.notifyQuestion(), seed.notifyMeeting(), seed.notifyRadiusKm()
		)));
	}

	public void removeOnLastDisconnect(Long userId) {
		snapshots.remove(userId);
		removeFromGeoHash(userId);
	}

	public void refreshLocation(Long userId, double latitude, double longitude) {
		snapshots.computeIfPresent(userId, (ignored, snapshot) -> {
			PresenceSnapshot refreshed = new PresenceSnapshot(latitude, longitude, snapshot.notifyAllEnabled(), snapshot.notifyQuestion(), snapshot.notifyMeeting(), snapshot.notifyRadiusKm());
			replaceGeoHash(userId, refreshed);
			return refreshed;
		});
	}

	public void refreshSettings(
		Long userId,
		boolean notifyAllEnabled,
		boolean notifyQuestion,
		boolean notifyMeeting,
		int notifyRadiusKm
	) {
		snapshots.computeIfPresent(userId, (ignored, snapshot) -> new PresenceSnapshot(
			snapshot.latitude(), snapshot.longitude(), notifyAllEnabled, notifyQuestion, notifyMeeting, notifyRadiusKm
		));
	}

	public Optional<PresenceSnapshot> findByUserId(Long userId) {
		return Optional.ofNullable(snapshots.get(userId));
	}

	public Map<Long, PresenceSnapshot> snapshots() {
		return Map.copyOf(snapshots);
	}

	public Set<Long> nearbyUserIds(double latitude, double longitude, int rings) {
		return GeoHashGrid.neighbors(latitude, longitude, rings).stream()
			.flatMap(key -> userIdsByGeoHash.getOrDefault(key, Set.of()).stream())
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private void replace(Long userId, PresenceSnapshot snapshot) {
		snapshots.put(userId, snapshot);
		replaceGeoHash(userId, snapshot);
	}

	private void replaceGeoHash(Long userId, PresenceSnapshot snapshot) {
		removeFromGeoHash(userId);
		if (snapshot.latitude() == null || snapshot.longitude() == null) return;
		String geoHash = GeoHashGrid.encode(snapshot.latitude(), snapshot.longitude());
		geoHashByUserId.put(userId, geoHash);
		userIdsByGeoHash.computeIfAbsent(geoHash, ignored -> ConcurrentHashMap.newKeySet()).add(userId);
	}

	private void removeFromGeoHash(Long userId) {
		String geoHash = geoHashByUserId.remove(userId);
		if (geoHash == null) return;
		userIdsByGeoHash.computeIfPresent(geoHash, (ignored, userIds) -> {
			userIds.remove(userId);
			return userIds.isEmpty() ? null : userIds;
		});
	}
}
