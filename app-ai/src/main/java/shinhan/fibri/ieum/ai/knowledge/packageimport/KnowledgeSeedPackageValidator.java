package shinhan.fibri.ieum.ai.knowledge.packageimport;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public final class KnowledgeSeedPackageValidator {

	private static final Pattern PACKAGE_KEY = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
	private static final Pattern PACKAGE_VERSION = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.\\d+$");
	private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<String> SOURCE_GRADES = Set.of("government", "public_agency");
	private static final Set<String> AUTHORITY_LEVELS = Set.of("national", "local");
	private static final Set<String> GEO_SCOPES = Set.of("general", "regional", "local", "place_specific");
	private static final Set<String> REGION_KEYS = Set.of("country", "sido", "sigungu", "eupMyeonDong", "place");
	private static final DateTimeFormatter STRICT_DATE = DateTimeFormatter
		.ofPattern("uuuu-MM-dd")
		.withResolverStyle(ResolverStyle.STRICT);

	private final KnowledgeSeedManifestHasher manifestHasher;

	public KnowledgeSeedPackageValidator(KnowledgeSeedManifestHasher manifestHasher) {
		this.manifestHasher = Objects.requireNonNull(manifestHasher, "manifestHasher must not be null");
	}

	void validateRawShape(JsonNode rawPackage) {
		require(rawPackage != null && rawPackage.isObject(), "knowledge package root must be an object");
		requireText(rawPackage, "schemaVersion", "schemaVersion");
		requireText(rawPackage, "packageKey", "packageKey");
		requireText(rawPackage, "packageVersion", "packageVersion");
		requireText(rawPackage, "canonicalDraft", "canonicalDraft");
		requireText(rawPackage, "documentType", "documentType");
		requireBoolean(rawPackage, "answerEligible", "answerEligible");
		requireText(rawPackage, "language", "language");
		requireText(rawPackage, "embeddingPolicy", "embeddingPolicy");
		requireText(rawPackage, "manifestHash", "manifestHash");
		requireIntegral(rawPackage, "expectedSourceCount", "expectedSourceCount");
		validateRawStringArray(required(rawPackage, "predicateVocabulary", "predicateVocabulary"), "predicateVocabulary");
		requireText(rawPackage, "reviewView", "reviewView");

		JsonNode sources = requireType(rawPackage, "sources", "sources", JsonNode::isArray, "an array");
		for (int index = 0; index < sources.size(); index++) {
			validateRawSource(sources.get(index), "sources[" + index + "]");
		}
	}

	public void validate(JsonNode rawPackage, KnowledgeSeedPackage seedPackage) {
		Objects.requireNonNull(seedPackage, "seedPackage must not be null");
		require("1.0".equals(seedPackage.schemaVersion()), "schemaVersion must be 1.0");
		require(matches(PACKAGE_KEY, seedPackage.packageKey()), "packageKey is invalid");
		require(matches(PACKAGE_VERSION, seedPackage.packageVersion()), "packageVersion is invalid");
		require(nonblank(seedPackage.canonicalDraft()), "canonicalDraft must not be blank");
		require("seed_knowledge_pack".equals(seedPackage.documentType()), "documentType is invalid");
		require(seedPackage.answerEligible(), "answerEligible must be true");
		require("ko".equals(seedPackage.language()), "language must be ko");
		require("one_source_one_chunk".equals(seedPackage.embeddingPolicy()), "embeddingPolicy is invalid");
		require(nonblank(seedPackage.reviewView()), "reviewView must not be blank");
		validateManifest(rawPackage, seedPackage.manifestHash());

		List<String> predicates = seedPackage.predicateVocabulary();
		require(predicates != null && !predicates.isEmpty(), "predicateVocabulary must be non-empty");
		validateNonblankUniqueStrings(predicates, "predicateVocabulary");
		predicates.forEach(predicate -> require(predicate.length() <= 120, "predicate must be at most 120 characters"));

		require(seedPackage.expectedSourceCount() > 0, "expectedSourceCount must be positive");
		List<KnowledgeSeedPackage.Source> sources = seedPackage.sources();
		require(sources != null && sources.size() == seedPackage.expectedSourceCount(), "sources count must match expectedSourceCount");
		Set<String> sourceKeys = new HashSet<>();
		Set<String> contentHashes = new HashSet<>();
		for (KnowledgeSeedPackage.Source source : sources) {
			require(source != null, "sources must not contain null");
			validateSource(seedPackage.packageKey(), predicates, source);
			require(sourceKeys.add(source.sourceKey()), "sourceKey must be unique");
			require(contentHashes.add(source.contentHash()), "contentHash must be unique");
		}
	}

	private void validateManifest(JsonNode rawPackage, String manifestHash) {
		require(matches(SHA_256, manifestHash), "manifestHash must be a lowercase SHA-256 hash");
		require(manifestHash.equals(manifestHasher.hash(rawPackage)), "manifestHash mismatch");
	}

	private void validateSource(
		String packageKey,
		List<String> predicates,
		KnowledgeSeedPackage.Source source
	) {
		require(nonblank(source.sourceKey()), "sourceKey must not be blank");
		require(nonblank(source.displayName()) && source.displayName().length() <= 200, "displayName must be 1 to 200 characters");
		require(("seed:" + packageKey + ':' + source.sourceKey()).length() <= 500, "seed document key must be at most 500 characters");
		require("knowledge_tip".equals(source.documentType()), "source documentType is invalid");
		require(source.answerEligible(), "source answerEligible must be true");
		require(SOURCE_GRADES.contains(source.sourceGrade()), "sourceGrade is invalid");
		require(AUTHORITY_LEVELS.contains(source.authorityLevel()), "authorityLevel is invalid");
		validateJurisdiction(source.jurisdiction());
		require(GEO_SCOPES.contains(source.geoScope()), "geoScope is invalid");
		validateRegionContext(source.regionContext(), source.geoScope());
		validateStringList(source.audience(), true, "audience");
		validateStringList(source.dependencies(), false, "dependencies");
		require(nonblank(source.riskDomain()), "riskDomain must not be blank");
		validateRequiredDate(source.retrievedAt(), "retrievedAt");
		validateRequiredDate(source.verifiedAt(), "verifiedAt");
		validateOptionalDate(source.effectiveFrom(), "effectiveFrom");
		validateOptionalDate(source.validUntil(), "validUntil");
		require(source.reviewIntervalDays() > 0, "reviewIntervalDays must be positive");
		validateHttpsUri(source.canonicalUrl(), "canonicalUrl");
		validateStringList(source.supportingUrls(), false, "supportingUrls");
		for (String supportingUrl : source.supportingUrls()) {
			validateHttpsUri(supportingUrl, "supportingUrls");
		}
		require(matches(SHA_256, source.contentHash()), "contentHash must be a lowercase SHA-256 hash");
		validateChunk(source);
		validateRelations(predicates, source.relations());
	}

	private void validateRawSource(JsonNode source, String path) {
		require(source != null && source.isObject(), path + " must be an object");
		requireText(source, "sourceKey", path + ".sourceKey");
		requireText(source, "displayName", path + ".displayName");
		requireText(source, "documentType", path + ".documentType");
		requireBoolean(source, "answerEligible", path + ".answerEligible");
		requireText(source, "sourceGrade", path + ".sourceGrade");
		requireText(source, "authorityLevel", path + ".authorityLevel");
		validateRawStringMap(required(source, "jurisdiction", path + ".jurisdiction"), path + ".jurisdiction");
		requireText(source, "geoScope", path + ".geoScope");
		validateRawStringMap(required(source, "regionContext", path + ".regionContext"), path + ".regionContext");
		validateRawStringArray(required(source, "audience", path + ".audience"), path + ".audience");
		validateRawStringArray(required(source, "dependencies", path + ".dependencies"), path + ".dependencies");
		requireText(source, "riskDomain", path + ".riskDomain");
		requireText(source, "retrievedAt", path + ".retrievedAt");
		requireText(source, "verifiedAt", path + ".verifiedAt");
		requireNullableText(source, "effectiveFrom", path + ".effectiveFrom");
		requireNullableText(source, "validUntil", path + ".validUntil");
		requireIntegral(source, "reviewIntervalDays", path + ".reviewIntervalDays");
		requireText(source, "canonicalUrl", path + ".canonicalUrl");

		JsonNode supportingUrls = source.get("supportingUrls");
		if (supportingUrls != null) {
			validateRawStringArray(supportingUrls, path + ".supportingUrls");
		}

		requireText(source, "contentHash", path + ".contentHash");
		JsonNode chunks = requireType(source, "chunks", path + ".chunks", JsonNode::isArray, "an array");
		for (int index = 0; index < chunks.size(); index++) {
			validateRawChunk(chunks.get(index), path + ".chunks[" + index + "]");
		}

		JsonNode relations = requireType(source, "relations", path + ".relations", JsonNode::isArray, "an array");
		for (int index = 0; index < relations.size(); index++) {
			validateRawRelation(relations.get(index), path + ".relations[" + index + "]");
		}
	}

	private void validateRawChunk(JsonNode chunk, String path) {
		require(chunk != null && chunk.isObject(), path + " must be an object");
		requireIntegral(chunk, "chunkOrder", path + ".chunkOrder");
		requireText(chunk, "text", path + ".text");
	}

	private void validateRawRelation(JsonNode relation, String path) {
		require(relation != null && relation.isObject(), path + " must be an object");
		requireText(relation, "subject", path + ".subject");
		requireText(relation, "predicate", path + ".predicate");
		requireText(relation, "object", path + ".object");
		requireType(relation, "confidence", path + ".confidence", JsonNode::isNumber, "a number");
		requireIntegral(relation, "evidenceChunkOrder", path + ".evidenceChunkOrder");
	}

	private void validateRawStringArray(JsonNode value, String path) {
		require(value != null && value.isArray(), path + " must be an array");
		for (int index = 0; index < value.size(); index++) {
			require(value.get(index).isTextual(), path + '[' + index + "] must be a string");
		}
	}

	private void validateRawStringMap(JsonNode value, String path) {
		require(value != null && value.isObject(), path + " must be an object");
		value.properties().forEach(entry ->
			require(entry.getValue().isTextual(), path + '.' + entry.getKey() + " must be a string")
		);
	}

	private JsonNode requireText(JsonNode object, String field, String path) {
		return requireType(object, field, path, JsonNode::isTextual, "a string");
	}

	private JsonNode requireBoolean(JsonNode object, String field, String path) {
		return requireType(object, field, path, JsonNode::isBoolean, "a boolean");
	}

	private JsonNode requireIntegral(JsonNode object, String field, String path) {
		return requireType(object, field, path, JsonNode::isIntegralNumber, "an integer");
	}

	private void requireNullableText(JsonNode object, String field, String path) {
		JsonNode value = required(object, field, path);
		require(value.isNull() || value.isTextual(), path + " must be a string or null");
	}

	private JsonNode requireType(
		JsonNode object,
		String field,
		String path,
		Predicate<JsonNode> type,
		String expectedType
	) {
		JsonNode value = required(object, field, path);
		require(type.test(value), path + " must be " + expectedType);
		return value;
	}

	private JsonNode required(JsonNode object, String field, String path) {
		JsonNode value = object.get(field);
		require(value != null, path + " is required");
		return value;
	}

	private void validateJurisdiction(Map<String, String> jurisdiction) {
		require(jurisdiction != null && jurisdiction.size() == 1, "jurisdiction must contain only country");
		require("KR".equals(jurisdiction.get("country")), "jurisdiction country must be KR");
	}

	private void validateRegionContext(Map<String, String> regionContext, String geoScope) {
		require(regionContext != null, "regionContext must not be null");
		for (Map.Entry<String, String> entry : regionContext.entrySet()) {
			require(REGION_KEYS.contains(entry.getKey()), "regionContext contains an unknown field");
			require(nonblank(entry.getValue()), "regionContext values must not be blank");
		}
		if (!"general".equals(geoScope)) {
			require(!regionContext.isEmpty(), "regionContext is required for scoped knowledge");
		}
	}

	private void validateChunk(KnowledgeSeedPackage.Source source) {
		List<KnowledgeSeedPackage.Chunk> chunks = source.chunks();
		require(chunks != null && chunks.size() == 1, "chunks must contain exactly one chunk");
		KnowledgeSeedPackage.Chunk chunk = chunks.getFirst();
		require(chunk != null, "chunks must not contain null");
		require(chunk.chunkOrder() == 0, "chunkOrder must be 0");
		require(nonblank(chunk.text()), "chunk text must not be blank");
		require(source.contentHash().equals(sha256(chunk.text())), "contentHash mismatch");
	}

	private void validateRelations(List<String> predicates, List<KnowledgeSeedPackage.Relation> relations) {
		require(relations != null && !relations.isEmpty(), "relations must be non-empty");
		Set<RelationKey> triples = new HashSet<>();
		for (KnowledgeSeedPackage.Relation relation : relations) {
			require(relation != null, "relations must not contain null");
			require(nonblank(relation.subject()) && relation.subject().length() <= 200, "subject must be 1 to 200 characters");
			require(nonblank(relation.predicate()) && relation.predicate().length() <= 120, "predicate must be 1 to 120 characters");
			require(predicates.contains(relation.predicate()), "predicate is not in predicateVocabulary");
			require(nonblank(relation.object()) && relation.object().length() <= 200, "object must be 1 to 200 characters");
			require(!relation.subject().equals(relation.object()), "self relation is forbidden");
			BigDecimal confidence = relation.confidence();
			require(confidence != null && confidence.compareTo(BigDecimal.ZERO) >= 0 && confidence.compareTo(BigDecimal.ONE) <= 0, "confidence must be between 0 and 1");
			require(relation.evidenceChunkOrder() == 0, "evidenceChunkOrder must be 0");
			require(
				triples.add(new RelationKey(relation.subject(), relation.predicate(), relation.object())),
				"duplicate relation is forbidden"
			);
		}
	}

	private void validateRequiredDate(String value, String field) {
		require(nonblank(value), field + " must not be blank");
		validateDate(value, field);
	}

	private void validateOptionalDate(String value, String field) {
		if (value != null) {
			validateDate(value, field);
		}
	}

	private void validateDate(String value, String field) {
		try {
			LocalDate.parse(value, STRICT_DATE);
		}
		catch (DateTimeParseException exception) {
			throw new KnowledgeSeedPackageValidationException(field + " must use uuuu-MM-dd");
		}
	}

	private void validateHttpsUri(String value, String field) {
		require(nonblank(value), field + " must not be blank");
		try {
			URI uri = new URI(value);
			require("https".equals(uri.getScheme()), field + " must use HTTPS");
			require(!uri.isOpaque() && uri.getHost() != null, field + " must be a hierarchical absolute URI with a host");
			require(uri.getUserInfo() == null, field + " must not contain userinfo");
		}
		catch (URISyntaxException exception) {
			throw new KnowledgeSeedPackageValidationException(field + " must be a valid URI");
		}
	}

	private void validateStringList(List<String> values, boolean nonEmpty, String field) {
		require(values != null, field + " must not be null");
		if (nonEmpty) {
			require(!values.isEmpty(), field + " must be non-empty");
		}
		for (String value : values) {
			require(nonblank(value), field + " must contain nonblank strings");
		}
	}

	private void validateNonblankUniqueStrings(List<String> values, String field) {
		Set<String> unique = new HashSet<>();
		for (String value : values) {
			require(nonblank(value), field + " must contain nonblank strings");
			require(unique.add(value), field + " must contain unique values");
		}
	}

	private String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is not available", exception);
		}
	}

	private boolean matches(Pattern pattern, String value) {
		return value != null && pattern.matcher(value).matches();
	}

	private boolean nonblank(String value) {
		return value != null && !value.isBlank();
	}

	private void require(boolean condition, String message) {
		if (!condition) {
			throw new KnowledgeSeedPackageValidationException(message);
		}
	}

	private record RelationKey(String subject, String predicate, String object) {
	}
}
