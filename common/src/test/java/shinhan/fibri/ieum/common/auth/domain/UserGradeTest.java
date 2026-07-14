package shinhan.fibri.ieum.common.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserGradeTest {

	@Test
	void fromAcceptedCountUsesConfiguredGradeThresholds() {
		assertThat(UserGrade.fromAcceptedCount(0)).isEqualTo(UserGrade.bronze);
		assertThat(UserGrade.fromAcceptedCount(4)).isEqualTo(UserGrade.bronze);
		assertThat(UserGrade.fromAcceptedCount(5)).isEqualTo(UserGrade.silver);
		assertThat(UserGrade.fromAcceptedCount(14)).isEqualTo(UserGrade.silver);
		assertThat(UserGrade.fromAcceptedCount(15)).isEqualTo(UserGrade.gold);
		assertThat(UserGrade.fromAcceptedCount(29)).isEqualTo(UserGrade.gold);
		assertThat(UserGrade.fromAcceptedCount(30)).isEqualTo(UserGrade.platinum);
		assertThat(UserGrade.fromAcceptedCount(49)).isEqualTo(UserGrade.platinum);
		assertThat(UserGrade.fromAcceptedCount(50)).isEqualTo(UserGrade.diamond);
	}
}
