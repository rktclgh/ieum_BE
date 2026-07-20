package shinhan.fibri.ieum.main.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class MeetingScheduleRepositoryIntegrationTest {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres")
	);

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		// 운영과 동일하게 native enum 캐스팅을 snake_case 로 렌더(예: 'completed'::meeting_schedule_status).
		// 기본 PostgreSQLDialect면 ::MeetingScheduleStatus 로 캐스팅해 42704가 난다.
		registry.add(
			"spring.jpa.properties.hibernate.dialect",
			() -> "shinhan.fibri.ieum.common.config.SnakeCasePostgreSQLDialect"
		);
	}

	@Autowired
	private MeetingScheduleRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchemaAndRows() {
		createSchema();
		jdbcTemplate.update("TRUNCATE TABLE meeting_schedules RESTART IDENTITY");
		jdbcTemplate.update("""
			INSERT INTO meeting_schedules (
				meeting_id, starts_on, start_time, starts_at, ends_at, visible_until, status, sequence_no, created_at, updated_at, deleted_at
			)
			VALUES
				(1, '2026-07-09', '10:00', '2026-07-09T10:00:00+09:00', NULL, '2026-07-09T23:59:59+09:00',
				 'scheduled'::meeting_schedule_status, 1, now(), now(), NULL),
				(1, '2026-07-08', '10:00', '2026-07-08T10:00:00+09:00', NULL, '2026-07-08T23:59:59+09:00',
				 'scheduled'::meeting_schedule_status, 2, now(), now(), NULL),
				(1, '2026-07-07', '10:00', '2026-07-07T10:00:00+09:00', NULL, '2026-07-07T23:59:59+09:00',
				 'scheduled'::meeting_schedule_status, 3, now(), now(), now()),
				(1, '2026-07-06', '10:00', '2026-07-06T10:00:00+09:00', NULL, '2026-07-06T23:59:59+09:00',
				 'cancelled'::meeting_schedule_status, 4, now(), now(), NULL)
			""");
	}

	@Test
	void completeExpiredSchedulesUsesVisibleUntilAndKeepsCurrentDayScheduled() {
		int updated = repository.completeExpiredSchedules(OffsetDateTime.parse("2026-07-09T20:00:00+09:00"));

		assertThat(updated).isEqualTo(1);
		assertThat(statusOf(1L)).isEqualTo("scheduled");
		assertThat(statusOf(2L)).isEqualTo("completed");
		assertThat(statusOf(3L)).isEqualTo("scheduled");
		assertThat(statusOf(4L)).isEqualTo("cancelled");
	}

	private String statusOf(Long scheduleId) {
		return jdbcTemplate.queryForObject(
			"SELECT status::text FROM meeting_schedules WHERE schedule_id = ?",
			String.class,
			scheduleId
		);
	}

	private void createSchema() {
		jdbcTemplate.execute("""
			DO $$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'meeting_schedule_status') THEN
					CREATE TYPE meeting_schedule_status AS ENUM ('scheduled', 'completed', 'cancelled');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS meeting_schedules (
				schedule_id BIGSERIAL PRIMARY KEY,
				meeting_id BIGINT NOT NULL,
				created_by BIGINT,
				starts_on DATE NOT NULL,
				start_time TIME,
				end_time TIME,
				starts_at TIMESTAMPTZ NOT NULL,
				ends_at TIMESTAMPTZ,
				visible_until TIMESTAMPTZ NOT NULL,
				status meeting_schedule_status NOT NULL DEFAULT 'scheduled',
				sequence_no INTEGER NOT NULL,
				created_at TIMESTAMPTZ NOT NULL,
				updated_at TIMESTAMPTZ NOT NULL,
				deleted_at TIMESTAMPTZ
			)
			""");
	}
}
