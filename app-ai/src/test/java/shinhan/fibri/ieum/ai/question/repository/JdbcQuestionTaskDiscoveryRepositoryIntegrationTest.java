package shinhan.fibri.ieum.ai.question.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcQuestionTaskDiscoveryRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_ai_discovery";

	private JdbcClient jdbc;
	private JdbcQuestionTaskDiscoveryRepository repository;

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
		repository = new JdbcQuestionTaskDiscoveryRepository(jdbc);
	}

	@Test
	void discoversOnlyActiveQuestionsWhosePinsAreAlsoActive() {
		long activeQuestionId = insertQuestion(false, false);
		long existingTaskQuestionId = insertQuestion(false, false);
		long deletedQuestionId = insertQuestion(true, false);
		long deletedPinQuestionId = insertQuestion(false, true);
		insertTask(existingTaskQuestionId);

		int discovered = repository.discover(20);

		assertThat(discovered).isEqualTo(1);
		assertThat(discoveredTaskIds()).containsExactly(activeQuestionId, existingTaskQuestionId);
		assertThat(discoveredTaskIds()).doesNotContain(deletedQuestionId, deletedPinQuestionId);
	}

	@Test
	void limitsDiscoveryToRequestedBatchSizeInQuestionIdOrder() {
		long firstQuestionId = insertQuestion(false, false);
		long secondQuestionId = insertQuestion(false, false);
		insertQuestion(false, false);

		int discovered = repository.discover(2);

		assertThat(discovered).isEqualTo(2);
		assertThat(discoveredTaskIds()).containsExactly(firstQuestionId, secondQuestionId);
	}

	@Test
	void concurrentDiscoveryCreatesOnlyOneTaskForTheSameQuestion() throws Exception {
		long questionId = insertQuestion(false, false);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Integer> first = executor.submit(() -> discoverAfterStart(ready, start));
			Future<Integer> second = executor.submit(() -> discoverAfterStart(ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
		}

		assertThat(discoveredTaskIds()).containsExactly(questionId);
	}

	@Test
	void concurrentDiscoveryRefillsItsBatchAfterConflictingWithAnotherWorker() throws Exception {
		long firstQuestionId = insertQuestion(false, false);
		for (int index = 1; index < 40; index++) {
			insertQuestion(false, false);
		}
		installFirstInsertBarrier(firstQuestionId);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<Integer> first = executor.submit(() -> discoverAfterStart(ready, start));
			Future<Integer> second = executor.submit(() -> discoverAfterStart(ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(40);
		}

		assertThat(discoveredTaskIds()).hasSize(40);
	}

	private int discoverAfterStart(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
		ready.countDown();
		assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
		return repository.discover(20);
	}

	private long insertQuestion(boolean deletedQuestion, boolean deletedPin) {
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "discovery-" + System.nanoTime() + "@example.com")
			.param("nickname", "discovery-" + System.nanoTime())
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address, deleted_at)
			VALUES (
				:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul',
				CASE WHEN :deletedPin THEN now() ELSE NULL END
			)
			RETURNING pin_id
			""")
			.param("userId", userId)
			.param("deletedPin", deletedPin)
			.query(Long.class)
			.single();
		return jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content, deleted_at)
			VALUES (
				:pinId, :userId, 'question title', 'question content',
				CASE WHEN :deletedQuestion THEN now() ELSE NULL END
			)
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.param("deletedQuestion", deletedQuestion)
			.query(Long.class)
			.single();
	}

	private void insertTask(long questionId) {
		jdbc.sql("INSERT INTO ai_question_tasks (question_id) VALUES (:questionId)")
			.param("questionId", questionId)
			.update();
	}

	private void installFirstInsertBarrier(long firstQuestionId) {
		jdbc.sql("""
			CREATE OR REPLACE FUNCTION ai_test_pause_first_task_insert()
			RETURNS TRIGGER
			LANGUAGE plpgsql
			AS $$
			BEGIN
			    IF NEW.question_id = %d THEN
			        PERFORM pg_sleep(1);
			    END IF;
			    RETURN NEW;
			END;
			$$;
			""".formatted(firstQuestionId)).update();
		jdbc.sql("""
			CREATE TRIGGER trg_ai_test_pause_first_task_insert
			BEFORE INSERT ON ai_question_tasks
			FOR EACH ROW EXECUTE FUNCTION ai_test_pause_first_task_insert()
			""").update();
	}

	private java.util.List<Long> discoveredTaskIds() {
		return jdbc.sql("SELECT question_id FROM ai_question_tasks ORDER BY question_id")
			.query(Long.class)
			.list();
	}
}
