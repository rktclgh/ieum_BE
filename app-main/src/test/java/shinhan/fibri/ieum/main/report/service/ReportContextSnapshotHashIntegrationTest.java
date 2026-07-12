package shinhan.fibri.ieum.main.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class ReportContextSnapshotHashIntegrationTest {

	private static final String DATABASE = "ieum_main_report_snapshot_hash";

	private JdbcClient jdbc;
	private ReportContextSnapshotFactory factory;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		factory = new ReportContextSnapshotFactory(new ObjectMapper().findAndRegisterModules(), jdbc);
	}

	@Test
	void producesTheSameCanonicalJsonAndHashAsPostgresJsonb() {
		ChatRoom room = room(100L);
		User sender = user(42L, "sender");
		Message reported = message(500L, room, sender, "reported", "2026-07-11T10:00:00+09:00");

		ReportContextSnapshot snapshot = factory.create(100L, java.util.List.of(), reported, java.util.List.of());
		CanonicalSnapshot expected = jdbc.sql("""
			SELECT (CAST(:snapshot AS jsonb))::text AS json,
			       encode(digest(convert_to((CAST(:snapshot AS jsonb))::text, 'UTF8'), 'sha256'), 'hex') AS hash
			""")
			.param("snapshot", snapshot.json())
			.query((rs, rowNumber) -> new CanonicalSnapshot(rs.getString("json"), rs.getString("hash")))
			.single();

		assertThat(snapshot.json()).isEqualTo(expected.json());
		assertThat(snapshot.hash()).isEqualTo(expected.hash());
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

	private void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record CanonicalSnapshot(String json, String hash) {
	}
}
