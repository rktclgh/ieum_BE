package shinhan.fibri.ieum.main.notification.presence;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PresenceRegistry {

	private final PresenceSeedRepository seedRepository;
	private final ConcurrentHashMap<Long, PresenceSnapshot> snapshots = new ConcurrentHashMap<>();

	public PresenceRegistry(PresenceSeedRepository seedRepository) {
		this.seedRepository = seedRepository;
	}

	public void seedOnConnect(Long userId) {
		seedRepository.findSeedByUserId(userId).ifPresent(seed -> snapshots.put(userId, new PresenceSnapshot(
			seed.latitude(), seed.longitude(), seed.notifyAllEnabled(), seed.notifyQuestion(), seed.notifyMeeting(), seed.notifyRadiusKm()
		)));
	}

	public void removeOnLastDisconnect(Long userId) {
		snapshots.remove(userId);
	}

	public void refreshLocation(Long userId, double latitude, double longitude) {
		snapshots.computeIfPresent(userId, (ignored, snapshot) -> new PresenceSnapshot(
			latitude, longitude, snapshot.notifyAllEnabled(), snapshot.notifyQuestion(), snapshot.notifyMeeting(), snapshot.notifyRadiusKm()
		));
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
}
