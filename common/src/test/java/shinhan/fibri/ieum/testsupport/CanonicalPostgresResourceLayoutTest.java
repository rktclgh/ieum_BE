package shinhan.fibri.ieum.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalPostgresResourceLayoutTest {

	@Test
	void packagesCanonicalDatabaseResourcesUnderNamespaceOnly() {
		ClassLoader loader = getClass().getClassLoader();

		assertThat(loader.getResource("canonical-db/schema.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/seed_countries.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v7_countries.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v8_meeting_place_name.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v8_pin_location_snapshot.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v9_meeting_schedules.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v12_ai_table_consolidation.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v13_app_ai_v2_expand.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v14_report_worklist_expand.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v15_question_ai_ticket_notification.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/migrations/v15_question_ai_trigger_notification.sql")).isNull();
		assertThat(loader.getResource("canonical-db/migrations/v15_question_recommendation_embedding_model.sql")).isNull();
		assertThat(loader.getResource("canonical-db/test-baselines/schema-v12.sql")).isNotNull();
		assertThat(loader.getResource("canonical-db/test-support/postgres-ai/Dockerfile")).isNotNull();
		assertThat(loader.getResource("schema.sql")).isNull();
	}
}
