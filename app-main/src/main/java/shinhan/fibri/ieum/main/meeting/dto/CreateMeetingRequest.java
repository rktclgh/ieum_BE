package shinhan.fibri.ieum.main.meeting.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateMeetingRequest(
	@NotBlank @Size(max = 200) String title,
	@Size(max = 2000) String content,
	@NotBlank @Size(max = 100) String placeName,
	@NotNull @Future OffsetDateTime meetingAt,
	@NotNull @Min(2) @Max(99) Integer maxMembers,
	@NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
	@NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
	UUID imageFileId
) {
}
