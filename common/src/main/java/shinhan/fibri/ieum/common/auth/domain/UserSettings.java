package shinhan.fibri.ieum.common.auth.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.util.Objects;

@Entity
@Table(name = "user_settings")
public class UserSettings {

	@Id
	private Long userId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false, length = 5)
	private String language;

	@Column(name = "camera_permission", nullable = false)
	private boolean cameraPermission;

	@Column(name = "push_permission", nullable = false)
	private boolean pushPermission;

	@Column(name = "notify_all", nullable = false)
	private boolean notifyAll;

	@Column(name = "notify_meeting", nullable = false)
	private boolean notifyMeeting;

	@Column(name = "notify_question", nullable = false)
	private boolean notifyQuestion;

	@Column(name = "notify_radius_km", nullable = false)
	private int notifyRadiusKm;

	protected UserSettings() {
	}

	private UserSettings(User user, String language) {
		this.user = Objects.requireNonNull(user, "user must not be null");
		this.language = Objects.requireNonNull(language, "language must not be null");
		this.cameraPermission = false;
		this.pushPermission = true;
		this.notifyAll = true;
		this.notifyMeeting = true;
		this.notifyQuestion = true;
		this.notifyRadiusKm = 5;
	}

	public static UserSettings defaultFor(User user) {
		return forSignup(user, "ko");
	}

	public static UserSettings forSignup(User user, String language) {
		return new UserSettings(user, language);
	}

	public Long getId() {
		return userId;
	}

	public User getUser() {
		return user;
	}

	public String getLanguage() {
		return language;
	}

	public boolean isCameraPermission() {
		return cameraPermission;
	}

	public boolean isPushPermission() {
		return pushPermission;
	}

	public boolean isNotifyAll() {
		return notifyAll;
	}

	public boolean isNotifyMeeting() {
		return notifyMeeting;
	}

	public boolean isNotifyQuestion() {
		return notifyQuestion;
	}

	public int getNotifyRadiusKm() {
		return notifyRadiusKm;
	}
}
