package shinhan.fibri.ieum.main.question.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record QuestionLocation(
	@NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
	@NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude
) {
}
