package shinhan.fibri.ieum.main.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;

class ReportContextSnapshotFactoryTest {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final ReportContextSnapshotFactory factory = new ReportContextSnapshotFactory(objectMapper);

	@Test
	void createsStableSchemaV1SnapshotAndHashWithoutNickname() throws Exception {
		ChatRoom room = room(100L);
		User sender = user(42L, "sender");
		Message before = message(499L, room, sender, "before", "2026-07-11T09:59:00+09:00");
		Message reported = message(500L, room, sender, "reported", "2026-07-11T10:00:00+09:00");
		Message after = message(501L, room, sender, "after", "2026-07-11T10:01:00+09:00");

		ReportContextSnapshot first = factory.create(100L, List.of(before), reported, List.of(after));
		ReportContextSnapshot second = factory.create(100L, List.of(before), reported, List.of(after));

		assertThat(first.json()).isEqualTo(second.json());
		assertThat(first.hash()).isEqualTo(second.hash());
		assertThat(first.hash()).isEqualTo(sha256(first.json()));
		var payload = objectMapper.readTree(first.json());
		assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
		assertThat(payload.path("reported").has("senderNickname")).isFalse();
		assertThat(payload.path("roomId").asLong()).isEqualTo(100L);
		assertThat(payload.path("reported").path("messageId").asLong()).isEqualTo(500L);
		assertThat(payload.path("before").get(0).path("senderId").asLong()).isEqualTo(42L);
		assertThat(payload.path("reported").has("imageFileId")).isTrue();
	}

	@Test
	void changesHashWhenMessageContentChanges() {
		ChatRoom room = room(100L);
		User sender = user(42L, "sender");
		Message original = message(500L, room, sender, "reported", "2026-07-11T10:00:00+09:00");
		Message changed = message(500L, room, sender, "changed", "2026-07-11T10:00:00+09:00");

		assertThat(factory.create(100L, List.of(), original, List.of()).hash())
			.isNotEqualTo(factory.create(100L, List.of(), changed, List.of()).hash());
	}

	@Test
	void changesHashWhenImageFileIdChanges() {
		ChatRoom room = room(100L);
		User sender = user(42L, "sender");
		Message first = image(500L, room, sender, UUID.fromString("11111111-1111-1111-1111-111111111111"));
		Message second = image(500L, room, sender, UUID.fromString("22222222-2222-2222-2222-222222222222"));

		assertThat(factory.create(100L, List.of(), first, List.of()).hash())
			.isNotEqualTo(factory.create(100L, List.of(), second, List.of()).hash());
	}

	@Test
	void preservesRepositoryContextOrderingAndIncludesReportedMessageOnce() throws Exception {
		ChatRoom room = room(100L);
		User sender = user(42L, "sender");
		Message beforeNewest = message(499L, room, sender, "before-newest", "2026-07-11T09:59:00+09:00");
		Message beforeOldest = message(498L, room, sender, "before-oldest", "2026-07-11T09:58:00+09:00");
		Message reported = message(500L, room, sender, "reported", "2026-07-11T10:00:00+09:00");
		Message afterOldest = message(501L, room, sender, "after-oldest", "2026-07-11T10:01:00+09:00");
		Message afterNewest = message(502L, room, sender, "after-newest", "2026-07-11T10:02:00+09:00");

		var payload = objectMapper.readTree(factory.create(
			100L,
			List.of(beforeNewest, beforeOldest),
			reported,
			List.of(afterOldest, afterNewest)
		).json());

		assertThat(payload.at("/before/0/messageId").asLong()).isEqualTo(499L);
		assertThat(payload.at("/before/1/messageId").asLong()).isEqualTo(498L);
		assertThat(payload.at("/reported/messageId").asLong()).isEqualTo(500L);
		assertThat(payload.at("/after/0/messageId").asLong()).isEqualTo(501L);
		assertThat(payload.at("/after/1/messageId").asLong()).isEqualTo(502L);
	}

	private String sha256(String value) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	private ChatRoom room(Long id) {
		ChatRoom room = ChatRoom.direct(42L, 77L);
		setField(room, "id", id);
		return room;
	}

	private User user(Long id, String nickname) {
		User user = User.createEmailUser(
			"user" + id + "@example.com",
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setField(user, "id", id);
		return user;
	}

	private Message message(Long id, ChatRoom room, User sender, String content, String createdAt) {
		Message message = Message.text(room, sender, content, OffsetDateTime.parse(createdAt));
		setField(message, "id", id);
		return message;
	}

	private Message image(Long id, ChatRoom room, User sender, UUID fileId) {
		Message message = Message.image(room, sender, fileId, OffsetDateTime.parse("2026-07-11T10:00:00+09:00"));
		setField(message, "id", id);
		return message;
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
