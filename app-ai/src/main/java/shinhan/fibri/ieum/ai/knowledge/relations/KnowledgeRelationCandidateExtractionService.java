package shinhan.fibri.ieum.ai.knowledge.relations;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

public final class KnowledgeRelationCandidateExtractionService {

	private static final int MAX_CANDIDATES = 5;
	private static final int MAX_TERM_CODE_POINTS = 200;
	private static final int MAX_EVIDENCE_CODE_POINTS = 200;

	private final KnowledgeRelationCandidateRepository repository;
	private final KnowledgeRelationCandidateExtractor extractor;
	private final Duration taskLease;
	private final Duration retryDelay;
	private final int maxAttempts;

	public KnowledgeRelationCandidateExtractionService(
		KnowledgeRelationCandidateRepository repository,
		KnowledgeRelationCandidateExtractor extractor,
		Duration taskLease,
		Duration retryDelay,
		int maxAttempts
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
		this.taskLease = Objects.requireNonNull(taskLease, "taskLease must not be null");
		this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay must not be null");
		this.maxAttempts = maxAttempts;
	}

	public boolean processNext() {
		return repository.claimNext(taskLease, maxAttempts)
			.map(this::process)
			.orElse(false);
	}

	private boolean process(ClaimedKnowledgeRelationExtractionTask task) {
		try {
			CandidateExtractionResult result = extractor.extract(task.document());
			List<KnowledgeRelationCandidate> candidates = validate(result, task);
			repository.completeWithCandidates(task, candidates, result.provider(), result.model());
		}
		catch (InvalidKnowledgeRelationExtractionOutputException exception) {
			repository.completeInvalid(task, exception.getMessage());
		}
		catch (KnowledgeRelationExtractionProviderException exception) {
			repository.markProviderFailure(task, retryDelay, maxAttempts, exception.getMessage());
		}
		return true;
	}

	private List<KnowledgeRelationCandidate> validate(
		CandidateExtractionResult result,
		ClaimedKnowledgeRelationExtractionTask task
	) {
		if (result == null) {
			throw invalid("empty extraction output");
		}
		List<KnowledgeRelationCandidate> candidates = new ArrayList<>();
		for (ExtractedKnowledgeRelationCandidate candidate : result.candidates()) {
			if (candidates.size() == MAX_CANDIDATES) {
				break;
			}
			candidates.add(validateCandidate(candidate, task.document().chunkText()));
		}
		return List.copyOf(candidates);
	}

	private KnowledgeRelationCandidate validateCandidate(
		ExtractedKnowledgeRelationCandidate candidate,
		String document
	) {
		if (candidate == null) {
			throw invalid("candidate must not be null");
		}
		String subject = term(candidate.subject(), "subject");
		String object = term(candidate.object(), "object");
		KnowledgeRelationPredicate predicate = predicate(candidate.predicate());
		String evidence = evidence(candidate.evidence(), document);
		if (!Double.isFinite(candidate.confidence()) || candidate.confidence() < 0 || candidate.confidence() > 1) {
			throw invalid("confidence must be between 0 and 1");
		}
		return new KnowledgeRelationCandidate(
			subject,
			predicate,
			object,
			candidate.confidence(),
			evidence
		);
	}

	private String term(String value, String name) {
		String trimmed = required(value, name);
		int codePoints = trimmed.codePointCount(0, trimmed.length());
		if (codePoints > MAX_TERM_CODE_POINTS) {
			throw invalid(name + " must not exceed 200 characters");
		}
		return trimmed;
	}

	private KnowledgeRelationPredicate predicate(String value) {
		String trimmed = required(value, "predicate");
		try {
			return KnowledgeRelationPredicate.valueOf(trimmed);
		}
		catch (IllegalArgumentException exception) {
			throw invalid("unsupported predicate");
		}
	}

	private String evidence(String value, String document) {
		String trimmed = required(value, "evidence");
		String documentSubstring = documentSubstring(trimmed, document);
		int codePoints = documentSubstring.codePointCount(0, documentSubstring.length());
		if (codePoints < 1 || codePoints > MAX_EVIDENCE_CODE_POINTS) {
			throw invalid("evidence must contain 1 to 200 Unicode code points");
		}
		return documentSubstring;
	}

	private String documentSubstring(String value, String document) {
		if (document.contains(value)) {
			return value;
		}
		String normalizedValue = Normalizer.normalize(value, Normalizer.Form.NFC);
		for (int start = 0; start < document.length(); start = document.offsetByCodePoints(start, 1)) {
			int end = start;
			for (int codePoints = 0; codePoints < MAX_EVIDENCE_CODE_POINTS && end < document.length(); codePoints++) {
				end = document.offsetByCodePoints(end, 1);
				String substring = document.substring(start, end);
				if (Normalizer.normalize(substring, Normalizer.Form.NFC).equals(normalizedValue)) {
					return substring;
				}
			}
		}
		throw invalid("evidence must be a document substring");
	}

	private String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw invalid(name + " must not be blank");
		}
		return value.trim();
	}

	private InvalidKnowledgeRelationExtractionOutputException invalid(String message) {
		return new InvalidKnowledgeRelationExtractionOutputException(message);
	}

}
