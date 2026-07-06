package shinhan.fibri.ieum.common.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import shinhan.fibri.ieum.common.auth.validation.AuthEmailNormalizer;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "user_id")
	private Long id;

	@Column(nullable = false, length = 254)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(name = "birth_date", nullable = false)
	private LocalDate birthDate;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "varchar(30)")
	private AuthProvider provider;

	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "varchar(30)")
	private UserRole role;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "varchar(30)")
	private UserStatus status;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "varchar(30)")
	private UserGrade grade;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(columnDefinition = "varchar(30)")
	private GenderType gender;

	@Column(length = 2)
	private String nationality;

	@Column(name = "accepted_count", nullable = false)
	private int acceptedCount;

	@Column(name = "password_reset_required", nullable = false)
	private boolean passwordResetRequired;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;

	protected User() {
	}

	private User(
		String email,
		String passwordHash,
		String nickname,
		LocalDate birthDate,
		GenderType gender,
		String nationality
	) {
		this.email = AuthEmailNormalizer.normalize(email);
		this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
		this.nickname = Objects.requireNonNull(nickname, "nickname must not be null");
		this.birthDate = Objects.requireNonNull(birthDate, "birthDate must not be null");
		this.gender = Objects.requireNonNull(gender, "gender must not be null");
		this.nationality = Objects.requireNonNull(nationality, "nationality must not be null");
		this.provider = AuthProvider.email;
		this.emailVerified = true;
		this.role = UserRole.user;
		this.status = UserStatus.active;
		this.grade = UserGrade.bronze;
		this.acceptedCount = 0;
		this.passwordResetRequired = false;
	}

	public static User createEmailUser(
		String email,
		String passwordHash,
		String nickname,
		LocalDate birthDate,
		GenderType gender,
		String nationality
	) {
		return new User(email, passwordHash, nickname, birthDate, gender, nationality);
	}

	public void markDeleted(OffsetDateTime deletedAt) {
		this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getNickname() {
		return nickname;
	}

	public LocalDate getBirthDate() {
		return birthDate;
	}

	public AuthProvider getProvider() {
		return provider;
	}

	public boolean isEmailVerified() {
		return emailVerified;
	}

	public UserRole getRole() {
		return role;
	}

	public UserStatus getStatus() {
		return status;
	}

	public UserGrade getGrade() {
		return grade;
	}

	public GenderType getGender() {
		return gender;
	}

	public String getNationality() {
		return nationality;
	}

	public int getAcceptedCount() {
		return acceptedCount;
	}

	public boolean isPasswordResetRequired() {
		return passwordResetRequired;
	}

	public OffsetDateTime getDeletedAt() {
		return deletedAt;
	}
}
