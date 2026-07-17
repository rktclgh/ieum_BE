package shinhan.fibri.ieum.main.translation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TranslationRequest(
	@NotBlank
	@Size(max = 5000)
	String text,

	@NotNull
	@Pattern(regexp = "ko|en|ja|zh|vi|th|ru")
	String targetLang
) {
}
