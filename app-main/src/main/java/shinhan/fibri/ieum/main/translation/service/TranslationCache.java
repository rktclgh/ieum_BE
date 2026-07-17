package shinhan.fibri.ieum.main.translation.service;

import java.util.Optional;
import shinhan.fibri.ieum.main.translation.dto.TranslationResponse;

public interface TranslationCache {

	Optional<TranslationResponse> get(TranslationSubjectKind kind, Long subjectId, TargetLanguage targetLanguage);

	void put(TranslationSubjectKind kind, Long subjectId, TargetLanguage targetLanguage, TranslationResponse response);
}
