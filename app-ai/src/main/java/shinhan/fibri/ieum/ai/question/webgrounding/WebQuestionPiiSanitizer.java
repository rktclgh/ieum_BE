package shinhan.fibri.ieum.ai.question.webgrounding;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;

public final class WebQuestionPiiSanitizer {

	static final String REDACTION_TOKEN = "[REDACTED]";
	private static final String NON_ADDRESS_QUANTITY_SUFFIX =
		"(?:분|초|시간|번(?!지)|회|개|명|원|년|개월|일|%|km|킬로미터|m|미터)";

	private static final Pattern COORDINATE_PAIR = Pattern.compile(
		"(?<![\\p{L}\\p{N}.])[-+]?\\d{1,3}\\.\\d{3,}\\s*[,/]\\s*[-+]?\\d{1,3}\\.\\d{3,}(?![\\p{L}\\p{N}.])"
	);
	private static final Pattern EMAIL = Pattern.compile(
		"(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}"
	);
	private static final Pattern KOREAN_PHONE = Pattern.compile(
		"(?<!\\d)(?:\\(?(?:01[016789]|02|0[3-6][1-5]|070|050\\d?)\\)?[ .-]?"
			+ "\\d{3,4}[ .-]?\\d{4}|1[568]\\d{2}[ .-]?\\d{4})(?!\\d)"
	);
	private static final Pattern KOREAN_RESIDENT_NUMBER = Pattern.compile(
		"(?<!\\d)\\d{6}[ -]?[1-8]\\d{6}(?!\\d)"
	);
	private static final Pattern LONG_FINANCIAL_NUMBER = Pattern.compile(
		"(?<!\\d)(?:\\d[ .-]?){11,18}\\d(?!\\d)"
	);
	private static final Pattern LABELED_COORDINATE = Pattern.compile(
		"(?i)(?<![\\p{L}\\p{N}])(?:위도|경도|latitude|longitude|lat|lng)\\s*[:=]?\\s*"
			+ "[-+]?\\d{1,3}(?:\\.\\d+)?(?![\\p{L}\\p{N}.])"
	);
	private static final Pattern KOREAN_ROAD_ADDRESS = Pattern.compile(
		"(?<![\\p{L}\\p{N}])[\\p{IsHangul}]{1,30}(?:대로|로|길)\\s*"
			+ "\\d{1,5}(?:-\\d{1,5})?(?:\\s*번지)?(?!\\d)(?!\\s*"
			+ NON_ADDRESS_QUANTITY_SUFFIX + ")"
	);
	private static final Pattern KOREAN_LOT_ADDRESS = Pattern.compile(
		"(?<![\\p{L}\\p{N}])[\\p{IsHangul}\\d]{1,30}(?:동|읍|면|리|가)\\s+"
			+ "\\d{1,5}(?:-\\d{1,5})?(?:\\s*번지)?(?!\\d)(?!\\s*"
			+ NON_ADDRESS_QUANTITY_SUFFIX + ")"
	);
	private static final Pattern KOREAN_BUILDING_UNIT = Pattern.compile(
		"(?<!\\d)\\d{1,5}\\s*(?:동|호|층)"
			+ "(?=$|[\\s,./()\\-]|에서|으로|에|의|앞|뒤|근처|입니다|이에요)"
	);
	private static final Pattern FLEXIBLE_SEPARATOR = Pattern.compile("[\\s\\p{P}\\p{S}]+");
	private static final String FLEXIBLE_SEPARATOR_REGEX = "[\\s\\p{P}\\p{S}]*";
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern MEANINGFUL_CHARACTER = Pattern.compile("[\\p{L}\\p{N}]");
	private static final List<Pattern> PII_PATTERNS = List.of(
		COORDINATE_PAIR,
		EMAIL,
		KOREAN_PHONE,
		KOREAN_RESIDENT_NUMBER,
		LONG_FINANCIAL_NUMBER,
		LABELED_COORDINATE,
		KOREAN_ROAD_ADDRESS,
		KOREAN_LOT_ADDRESS,
		KOREAN_BUILDING_UNIT
	);

	public String sanitize(String value, StoredLocationSnapshot location) {
		Objects.requireNonNull(value, "value must not be null");
		Objects.requireNonNull(location, "location must not be null");

		String sanitized = normalize(value);
		for (String storedLocationText : storedLocationTexts(location)) {
			sanitized = redactFlexibleLiteral(sanitized, storedLocationText);
		}
		for (Pattern piiPattern : PII_PATTERNS) {
			sanitized = piiPattern.matcher(sanitized).replaceAll(Matcher.quoteReplacement(REDACTION_TOKEN));
		}
		sanitized = redactStoredCoordinate(sanitized, location.latitude());
		sanitized = redactStoredCoordinate(sanitized, location.longitude());
		return WHITESPACE.matcher(sanitized.strip()).replaceAll(" ");
	}

	public boolean hasMeaningfulText(String sanitized) {
		Objects.requireNonNull(sanitized, "sanitized must not be null");
		String withoutRedactions = sanitized.replace(REDACTION_TOKEN, "");
		return MEANINGFUL_CHARACTER.matcher(withoutRedactions).find();
	}

	private static List<String> storedLocationTexts(StoredLocationSnapshot location) {
		return Stream.of(location.address(), location.detailAddress(), location.label())
			.filter(value -> value != null && !value.isBlank())
			.map(String::trim)
			.distinct()
			.sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
			.toList();
	}

	private static String redactFlexibleLiteral(String source, String sensitiveValue) {
		String[] tokens = FLEXIBLE_SEPARATOR.split(normalize(sensitiveValue).strip());
		StringBuilder expression = new StringBuilder();
		for (String token : tokens) {
			if (token.isBlank()) {
				continue;
			}
			if (!expression.isEmpty()) {
				expression.append(FLEXIBLE_SEPARATOR_REGEX);
			}
			expression.append(Pattern.quote(token));
		}
		if (expression.isEmpty()) {
			return source;
		}
		return Pattern.compile(expression.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
			.matcher(source)
			.replaceAll(Matcher.quoteReplacement(REDACTION_TOKEN));
	}

	private static String redactStoredCoordinate(String source, double coordinate) {
		String canonical = BigDecimal.valueOf(coordinate).stripTrailingZeros().toPlainString();
		String numericExpression;
		if (canonical.startsWith("-")) {
			numericExpression = Pattern.quote(canonical);
		} else {
			numericExpression = "\\+?" + Pattern.quote(canonical);
		}
		if (canonical.indexOf('.') >= 0) {
			numericExpression += "0*";
		} else {
			numericExpression += "(?:\\.0+)?";
		}
		return Pattern.compile(
			"(?<![\\p{L}\\p{N}.])" + numericExpression + "(?![\\p{L}\\p{N}.])"
		).matcher(source).replaceAll(Matcher.quoteReplacement(REDACTION_TOKEN));
	}

	private static String normalize(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC);
	}
}
