package shinhan.fibri.ieum.common.friend.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.common.friend.domain.Friendship;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

	@Query("""
		SELECT friendship
		FROM Friendship friendship
		WHERE (friendship.requester.id = :firstUserId AND friendship.addressee.id = :secondUserId)
		   OR (friendship.requester.id = :secondUserId AND friendship.addressee.id = :firstUserId)
		""")
	Optional<Friendship> findByUserPair(
		@Param("firstUserId") Long firstUserId,
		@Param("secondUserId") Long secondUserId
	);

	@Query("""
		SELECT friendship
		FROM Friendship friendship
		JOIN FETCH friendship.requester requester
		JOIN FETCH friendship.addressee addressee
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.accepted
		  AND (requester.id = :userId OR addressee.id = :userId)
		  AND requester.deletedAt IS NULL
		  AND addressee.deletedAt IS NULL
		ORDER BY friendship.updatedAt DESC, friendship.id DESC
		""")
	List<Friendship> findAcceptedByUserId(@Param("userId") Long userId);

	@Query("""
		SELECT friendship
		FROM Friendship friendship
		JOIN FETCH friendship.requester requester
		JOIN FETCH friendship.addressee addressee
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.pending
		  AND addressee.id = :userId
		  AND requester.deletedAt IS NULL
		  AND addressee.deletedAt IS NULL
		ORDER BY friendship.createdAt DESC, friendship.id DESC
		""")
	List<Friendship> findPendingReceivedByUserId(@Param("userId") Long userId);

	@Query("""
		SELECT friendship
		FROM Friendship friendship
		JOIN FETCH friendship.requester requester
		JOIN FETCH friendship.addressee addressee
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.pending
		  AND requester.id = :userId
		  AND requester.deletedAt IS NULL
		  AND addressee.deletedAt IS NULL
		ORDER BY friendship.createdAt DESC, friendship.id DESC
		""")
	List<Friendship> findPendingSentByUserId(@Param("userId") Long userId);

	@Query("""
		SELECT friendship
		FROM Friendship friendship
		JOIN FETCH friendship.requester requester
		JOIN FETCH friendship.addressee addressee
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.blocked
		  AND friendship.blockedBy.id = :userId
		  AND requester.deletedAt IS NULL
		  AND addressee.deletedAt IS NULL
		ORDER BY friendship.updatedAt DESC, friendship.id DESC
		""")
	List<Friendship> findBlockedByUserId(@Param("userId") Long userId);

	@Query("""
		SELECT DISTINCT CASE
			WHEN friendship.requester.id = :userId THEN friendship.addressee.id
			ELSE friendship.requester.id
		END
		FROM Friendship friendship
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.blocked
		  AND (friendship.requester.id = :userId OR friendship.addressee.id = :userId)
		  AND friendship.requester.deletedAt IS NULL
		  AND friendship.addressee.deletedAt IS NULL
		""")
	List<Long> findBlockedUserIdsByUserId(@Param("userId") Long userId);

	@Query("""
		SELECT DISTINCT CASE
			WHEN friendship.requester.id = :userId THEN friendship.addressee.id
			ELSE friendship.requester.id
		END
		FROM Friendship friendship
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.accepted
		  AND (friendship.requester.id = :userId OR friendship.addressee.id = :userId)
		  AND friendship.requester.deletedAt IS NULL
		  AND friendship.addressee.deletedAt IS NULL
		""")
	List<Long> findAcceptedUserIdsByUserId(@Param("userId") Long userId);

	@Query("""
		SELECT COUNT(friendship) > 0
		FROM Friendship friendship
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.accepted
		  AND (
			(friendship.requester.id = :firstUserId AND friendship.addressee.id = :secondUserId)
			OR (friendship.requester.id = :secondUserId AND friendship.addressee.id = :firstUserId)
		  )
		""")
	boolean existsAcceptedByUserPair(
		@Param("firstUserId") Long firstUserId,
		@Param("secondUserId") Long secondUserId
	);

	@Query("""
		SELECT COUNT(friendship) > 0
		FROM Friendship friendship
		WHERE friendship.status = shinhan.fibri.ieum.common.friend.domain.FriendshipStatus.blocked
		  AND (
			(friendship.requester.id = :firstUserId AND friendship.addressee.id = :secondUserId)
			OR (friendship.requester.id = :secondUserId AND friendship.addressee.id = :firstUserId)
		  )
		""")
	boolean existsBlockedByUserPair(
		@Param("firstUserId") Long firstUserId,
		@Param("secondUserId") Long secondUserId
	);
}
