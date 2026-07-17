package shinhan.fibri.ieum.main.translation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TranslationRequest(
	@NotNull
	@Pattern(regexp = "ko|en|ja|zh|vi|th|ru")
	String targetLang
) {
}
