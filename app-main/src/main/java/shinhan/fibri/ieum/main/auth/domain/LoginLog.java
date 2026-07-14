package shinhan.fibri.ieum.main.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.User;

@Entity
@Table(name = "login_logs")
public class LoginLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "log_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "varchar(30)")
	private AuthProvider provider;

	@Column(name = "logged_in_at", nullable = false)
	private OffsetDateTime loggedInAt;

	protected LoginLog() {
	}

	private LoginLog(User user, AuthProvider provider, OffsetDateTime loggedInAt) {
		this.user = Objects.requireNonNull(user, "user must not be null");
		this.provider = Objects.requireNonNull(provider, "provider must not be null");
		this.loggedInAt = Objects.requireNonNull(loggedInAt, "loggedInAt must not be null");
	}

	public static LoginLog emailLogin(User user) {
		return new LoginLog(user, AuthProvider.email, OffsetDateTime.now());
	}

	public static LoginLog socialLogin(User user, AuthProvider provider) {
		return new LoginLog(user, provider, OffsetDateTime.now());
	}

	public Long getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public AuthProvider getProvider() {
		return provider;
	}

	public OffsetDateTime getLoggedInAt() {
		return loggedInAt;
	}
}
