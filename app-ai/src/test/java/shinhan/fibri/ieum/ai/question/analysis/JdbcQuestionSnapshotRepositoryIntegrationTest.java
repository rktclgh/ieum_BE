package shinhan.fibri.ieum.ai.question.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcQuestionSnapshotRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_ai_question_snapshot";
	private static final AtomicLong SEQUENCE = new AtomicLong();

	private JdbcClient jdbc;
	private QuestionSnapshotRepository repository;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		repository = new JdbcQuestionSnapshotRepository(jdbc);
	}

	@Test
	void loadsOnlyTheImmutableQuestionAndStoredLocationFieldsInLatitudeLongitudeOrder() {
		long questionId = insertQuestion(false, false);

		QuestionInputSnapshot snapshot = repository.findActiveByQuestionId(questionId).orElseThrow();

		assertThat(snapshot.title()).isEqualTo("서울 시청 근처 버스 승차 방법");
		assertThat(snapshot.content()).isEqualTo("앞문으로 타는 게 맞나요?");
		assertThat(snapshot.location()).isEqualTo(new StoredLocationSnapshot(
			37.5665d,
			126.9780d,
			"서울특별시 중구 태평로1가 세종대로 110",
			"본관 앞",
			"서울시청"
		));
		assertThat(Arrays.stream(QuestionInputSnapshot.class.getRecordComponents())
			.map(component -> component.getName()))
			.containsExactly("title", "content", "location")
			.doesNotContain("userId", "authorId");
		assertThat(new StoredAddressRegionParser().parse(snapshot.location().address()))
			.isEqualTo(RegionContext.korea("서울특별시", "중구", "태평로1가", null));
	}

	@Test
	void returnsEmptyForMissingDeletedQuestionOrDeletedPin() {
		long deletedQuestionId = insertQuestion(true, false);
		long deletedPinId = insertQuestion(false, true);

		assertThat(repository.findActiveByQuestionId(Long.MAX_VALUE)).isEmpty();
		assertThat(repository.findActiveByQuestionId(deletedQuestionId)).isEmpty();
		assertThat(repository.findActiveByQuestionId(deletedPinId)).isEmpty();
	}

	@Test
	void excludesAQuestionRowThatReferencesANonQuestionPin() {
		long questionId = insertQuestion(false, false, "meeting");

		assertThat(repository.findActiveByQuestionId(questionId)).isEmpty();
	}

	@Test
	void normalizesLegacyNullOptionalLocationTextToEmptyStrings() {
		long questionId = insertQuestion(false, false);
		jdbc.sql("ALTER TABLE pins ALTER COLUMN detail_address DROP NOT NULL").update();
		jdbc.sql("ALTER TABLE pins ALTER COLUMN label DROP NOT NULL").update();
		jdbc.sql("""
			UPDATE pins
			SET detail_address = NULL,
			    label = NULL
			WHERE pin_id = (SELECT pin_id FROM questions WHERE question_id = :questionId)
			""")
			.param("questionId", questionId)
			.update();

		StoredLocationSnapshot location = repository.findActiveByQuestionId(questionId)
			.orElseThrow()
			.location();

		assertThat(location.detailAddress()).isEmpty();
		assertThat(location.label()).isEmpty();
	}

	@Test
	void rejectsNonPositiveQuestionIdsBeforeQuerying() {
		assertThatThrownBy(() -> repository.findActiveByQuestionId(0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("questionId");
	}

	private long insertQuestion(boolean deletedQuestion, boolean deletedPin) {
		return insertQuestion(deletedQuestion, deletedPin, "question");
	}

	private long insertQuestion(boolean deletedQuestion, boolean deletedPin, String pinType) {
		long sequence = SEQUENCE.incrementAndGet();
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "snapshot-" + sequence + "@example.com")
			.param("nickname", "snapshot-" + sequence)
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (
				author_id, pin_type, location, address, detail_address, label, deleted_at
			)
			VALUES (
				:userId,
				CAST(:pinType AS pin_type),
				ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
				'서울특별시 중구 태평로1가 세종대로 110',
				'본관 앞',
				'서울시청',
				CASE WHEN :deletedPin THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING pin_id
			""")
			.param("userId", userId)
			.param("pinType", pinType)
			.param("deletedPin", deletedPin)
			.query(Long.class)
			.single();
		return jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
			VALUES (
				:pinId,
				:userId,
				'서울 시청 근처 버스 승차 방법',
				'앞문으로 타는 게 맞나요?',
				CASE WHEN :deletedQuestion THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.param("deletedQuestion", deletedQuestion)
			.query(Long.class)
			.single();
	}
}
