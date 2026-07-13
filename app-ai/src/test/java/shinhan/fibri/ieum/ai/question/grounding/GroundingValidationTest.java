package shinhan.fibri.ieum.ai.question.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GroundingValidationTest {

	@Test
	void acceptsSupportedVerdictOnlyWhenUnsupportedClaimsAreEmpty() {
		GroundingValidation validation = new GroundingValidation(true, new BigDecimal("0.93"), List.of());

		assertThat(validation.supported()).isTrue();
		assertThat(validation.score()).isEqualByComparingTo("0.93");
		assertThat(validation.unsupportedClaims()).isEmpty();
		assertThatThrownBy(() -> new GroundingValidation(true, BigDecimal.ONE, List.of("unsupported")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("empty");
	}

	@Test
	void acceptsOneToEightTrimmedClaimsForAnUnsupportedVerdictAndCopiesThem() {
		List<String> mutableClaims = new ArrayList<>(List.of("  첫 번째 미지원 주장  "));
		GroundingValidation validation = new GroundingValidation(false, new BigDecimal("0.42"), mutableClaims);

		mutableClaims.clear();

		assertThat(validation.unsupportedClaims()).containsExactly("첫 번째 미지원 주장");
		assertThatThrownBy(() -> validation.unsupportedClaims().add("변경"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsUnsupportedVerdictsWithoutOneToEightBoundedNonblankClaims() {
		assertThatThrownBy(() -> new GroundingValidation(false, BigDecimal.ZERO, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");
		assertThatThrownBy(() -> new GroundingValidation(false, BigDecimal.ZERO, List.of("   ")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("blank");
		assertThatThrownBy(() -> new GroundingValidation(
			false,
			BigDecimal.ZERO,
			java.util.stream.IntStream.range(0, 9).mapToObj(index -> "claim " + index).toList()
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");
		assertThatThrownBy(() -> new GroundingValidation(
			false,
			BigDecimal.ZERO,
			List.of("가".repeat(501))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("too long");
	}

	@Test
	void rejectsMissingOrOutOfRangeScores() {
		assertThatThrownBy(() -> new GroundingValidation(true, null, List.of()))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("score");
		assertThatThrownBy(() -> new GroundingValidation(true, new BigDecimal("-0.01"), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("0 to 1");
		assertThatThrownBy(() -> new GroundingValidation(true, new BigDecimal("1.01"), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("0 to 1");
	}
}
