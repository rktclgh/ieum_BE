package shinhan.fibri.ieum.common.friend.domain;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import shinhan.fibri.ieum.common.auth.domain.User;

@Entity
@Table(name = "friendships")
public class Friendship {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "friendship_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requester_id", nullable = false)
	private User requester;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "addressee_id", nullable = false)
	private User addressee;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "varchar(30)")
	private FriendshipStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "blocked_by")
	private User blockedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	protected Friendship() {
	}

	private Friendship(User requester, User addressee, FriendshipStatus status, User blockedBy) {
		if (sameUserInstance(requester, addressee)) {
			throw new IllegalArgumentException("friendship participants must be different");
		}
		this.requester = Objects.requireNonNull(requester, "requester must not be null");
		this.addressee = Objects.requireNonNull(addressee, "addressee must not be null");
		this.status = Objects.requireNonNull(status, "status must not be null");
		if (status == FriendshipStatus.blocked) {
			assignBlockOwner(blockedBy);
		}
	}

	public static Friendship request(User requester, User addressee) {
		return new Friendship(requester, addressee, FriendshipStatus.pending, null);
	}

	public static Friendship blocked(User requester, User addressee, User blockedBy) {
		return new Friendship(requester, addressee, FriendshipStatus.blocked, blockedBy);
	}

	public void accept() {
		if (status != FriendshipStatus.pending) {
			throw new IllegalStateException("only a pending friendship can be accepted");
		}
		this.status = FriendshipStatus.accepted;
		this.blockedBy = null;
	}

	public void blockBy(User user) {
		this.status = FriendshipStatus.blocked;
		assignBlockOwner(user);
	}

	public User otherUser(Long userId) {
		if (Objects.equals(requester.getId(), userId)) {
			return addressee;
		}
		if (Objects.equals(addressee.getId(), userId)) {
			return requester;
		}
		throw new IllegalArgumentException("user is not a friendship participant");
	}

	private void assignBlockOwner(User user) {
		if (user == null || (!sameUserInstance(user, requester) && !sameUserInstance(user, addressee))) {
			throw new IllegalArgumentException("blockedBy must be a friendship participant");
		}
		this.blockedBy = user;
	}

	private boolean sameUserInstance(User first, User second) {
		if (first == null || second == null) {
			return false;
		}
		if (first == second) {
			return true;
		}
		return first.getId() != null && Objects.equals(first.getId(), second.getId());
	}

	public Long getId() {
		return id;
	}

	public User getRequester() {
		return requester;
	}

	public User getAddressee() {
		return addressee;
	}

	public FriendshipStatus getStatus() {
		return status;
	}

	public User getBlockedBy() {
		return blockedBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}
}
