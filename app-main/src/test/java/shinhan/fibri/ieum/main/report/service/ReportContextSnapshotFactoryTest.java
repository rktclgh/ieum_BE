package shinhan.fibri.ieum.main.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class ReportContextSnapshotFactoryTest {

	private static final String DATABASE = "ieum_main_report_snapshot_factory";
	private static JdbcClient jdbc;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private ReportContextSnapshotFactory factory;

	@BeforeAll
	static void setUpDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@BeforeEach
	void setUpFactory() {
		factory = new ReportContextSnapshotFactory(objectMapper, jdbc);
	}

	@Test
	void preservesStableSchemaV1MessageSnapshot() throws Exception {
		ChatRoom room = room(100L);
		User sender = user(42L, "sender");
		Message before = message(499L, room, sender, "before", "2026-07-11T09:59:00+09:00");
		Message reported = message(500L, room, sender, "reported", "2026-07-11T10:00:00+09:00");
		Message after = message(501L, room, sender, "after", "2026-07-11T10:01:00+09:00");

		ReportContextSnapshot first = factory.create(100L, List.of(before), reported, List.of(after));
		ReportContextSnapshot second = factory.create(100L, List.of(before), reported, List.of(after));

		assertThat(first.json()).isEqualTo(second.json());
		String legacyCanonicalJson = String.join(
			"",
			"{\"after\": [{\"content\": \"after\", \"senderId\": 42, ",
			"\"createdAt\": 1783731660.000000000, \"messageId\": 501, \"imageFileId\": null}], ",
			"\"before\": [{\"content\": \"before\", \"senderId\": 42, ",
			"\"createdAt\": 1783731540.000000000, \"messageId\": 499, \"imageFileId\": null}], ",
			"\"roomId\": 100, \"reported\": {\"content\": \"reported\", \"senderId\": 42, ",
			"\"createdAt\": 1783731600.000000000, \"messageId\": 500, \"imageFileId\": null}, ",
			"\"schemaVersion\": 1}"
		);
		assertThat(first.json()).isEqualTo(legacyCanonicalJson);
		assertThat(first.hash()).isEqualTo(second.hash());
		assertThat(first.hash()).matches("[0-9a-f]{64}");
		var payload = objectMapper.readTree(first.json());
		assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
		assertThat(payload.has("targetType")).isFalse();
		assertThat(payload.path("reported").has("senderNickname")).isFalse();
		assertThat(payload.path("roomId").asLong()).isEqualTo(100L);
		assertThat(payload.path("reported").path("messageId").asLong()).isEqualTo(500L);
		assertThat(payload.path("before").get(0).path("senderId").asLong()).isEqualTo(42L);
		assertThat(payload.path("reported").has("imageFileId")).isTrue();
	}

	@Test
	void createsStableHumanAnswerSnapshotBySortingImages() throws Exception {
		Answer answer = answer(500L, Answer.createHuman(10L, 77L, "human answer"), "2026-07-11T10:00:00+09:00");
		AnswerImage first = AnswerImage.link(
			500L,
			UUID.fromString("11111111-1111-1111-1111-111111111111"),
			0
		);
		AnswerImage second = AnswerImage.link(
			500L,
			UUID.fromString("22222222-2222-2222-2222-222222222222"),
			1
		);

		ReportContextSnapshot snapshot = factory.createAnswer(answer, List.of(second, first));
		ReportContextSnapshot orderedSnapshot = factory.createAnswer(answer, List.of(first, second));
		var payload = objectMapper.readTree(snapshot.json());

		assertThat(payload.path("schemaVersion").asInt()).isEqualTo(1);
		assertThat(payload.path("targetType").asText()).isEqualTo("answer");
		assertThat(payload.path("questionId").asLong()).isEqualTo(10L);
		assertThat(payload.at("/reported/answerId").asLong()).isEqualTo(500L);
		assertThat(payload.at("/reported/authorId").asLong()).isEqualTo(77L);
		assertThat(payload.at("/reported/isAi").asBoolean()).isFalse();
		assertThat(payload.at("/reported/content").asText()).isEqualTo("human answer");
		assertThat(payload.at("/reported/imageFileIds/0").asText())
			.isEqualTo("11111111-1111-1111-1111-111111111111");
		assertThat(payload.at("/reported/imageFileIds/1").asText())
			.isEqualTo("22222222-2222-2222-2222-222222222222");
		assertThat(snapshot.hash()).matches("[0-9a-f]{64}");
		assertThat(orderedSnapshot).isEqualTo(snapshot);
	}

	@Test
	void createsAiAnswerSnapshotWithExplicitNullAuthor() throws Exception {
		Answer answer = answer(501L, Answer.createAi(10L, "AI answer"), "2026-07-11T10:00:00+09:00");

		var payload = objectMapper.readTree(factory.createAnswer(answer, List.of()).json());

		assertThat(payload.at("/reported/isAi").asBoolean()).isTrue();
		assertThat(payload.at("/reported/authorId").isNull()).isTrue();
		assertThat(payload.at("/reported/imageFileIds").isArray()).isTrue();
		assertThat(payload.at("/reported/imageFileIds").size()).isZero();
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

	private Answer answer(Long id, Answer answer, String createdAt) {
		setField(answer, "id", id);
		setField(answer, "createdAt", OffsetDateTime.parse(createdAt));
		return answer;
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
