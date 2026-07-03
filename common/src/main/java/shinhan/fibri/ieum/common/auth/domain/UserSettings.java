package shinhan.fibri.ieum.common.auth.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
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

	protected UserSettings() {
	}

	private UserSettings(User user) {
		this.user = Objects.requireNonNull(user, "user must not be null");
	}

	public static UserSettings defaultFor(User user) {
		return new UserSettings(user);
	}

	public Long getId() {
		return userId;
	}

	public User getUser() {
		return user;
	}
}
