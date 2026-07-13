package shinhan.fibri.ieum.ai.question.analysis;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class StoredAddressRegionParser {

	private static final String KOREA_PREFIX = "대한민국";
	private static final String SEJONG = "세종특별자치시";
	private static final Pattern NUMBERED_LEGAL_DONG = Pattern.compile("^[가-힣]+\\d+가$");
	private static final Map<String, String> SIDO_ALIASES = Map.ofEntries(
		entry("서울", "서울특별시"), entry("서울시", "서울특별시"), entry("서울특별시", "서울특별시"),
		entry("부산", "부산광역시"), entry("부산시", "부산광역시"), entry("부산광역시", "부산광역시"),
		entry("대구", "대구광역시"), entry("대구시", "대구광역시"), entry("대구광역시", "대구광역시"),
		entry("인천", "인천광역시"), entry("인천시", "인천광역시"), entry("인천광역시", "인천광역시"),
		entry("광주", "광주광역시"), entry("광주시", "광주광역시"), entry("광주광역시", "광주광역시"),
		entry("대전", "대전광역시"), entry("대전시", "대전광역시"), entry("대전광역시", "대전광역시"),
		entry("울산", "울산광역시"), entry("울산시", "울산광역시"), entry("울산광역시", "울산광역시"),
		entry("세종", SEJONG), entry("세종시", SEJONG), entry(SEJONG, SEJONG),
		entry("경기", "경기도"), entry("경기도", "경기도"),
		entry("강원", "강원특별자치도"), entry("강원도", "강원특별자치도"),
		entry("강원특별자치도", "강원특별자치도"),
		entry("충북", "충청북도"), entry("충청북도", "충청북도"),
		entry("충남", "충청남도"), entry("충청남도", "충청남도"),
		entry("전북", "전북특별자치도"), entry("전라북도", "전북특별자치도"),
		entry("전북특별자치도", "전북특별자치도"),
		entry("전남", "전라남도"), entry("전라남도", "전라남도"),
		entry("경북", "경상북도"), entry("경상북도", "경상북도"),
		entry("경남", "경상남도"), entry("경상남도", "경상남도"),
		entry("제주", "제주특별자치도"), entry("제주도", "제주특별자치도"),
		entry("제주특별자치도", "제주특별자치도")
	);
	private static final Set<String> PROVINCES = Set.of(
		"경기도",
		"강원특별자치도",
		"충청북도",
		"충청남도",
		"전북특별자치도",
		"전라남도",
		"경상북도",
		"경상남도",
		"제주특별자치도"
	);

	public RegionContext parse(String address) {
		if (address == null || address.isBlank()) {
			return RegionContext.empty();
		}
		String[] tokens = address.trim().split("\\s+");
		int index = 0;
		if (KOREA_PREFIX.equals(cleanToken(tokens[index]))) {
			index++;
		}
		if (index >= tokens.length) {
			return RegionContext.empty();
		}
		String sido = SIDO_ALIASES.get(cleanToken(tokens[index]));
		if (sido == null) {
			return RegionContext.empty();
		}
		index++;

		String sigungu = null;
		String eupMyeonDong = null;
		if (SEJONG.equals(sido)) {
			if (index < tokens.length && isEupMyeonDong(tokens[index])) {
				eupMyeonDong = cleanToken(tokens[index]);
			}
			return RegionContext.korea(sido, null, eupMyeonDong, null);
		}

		if (index < tokens.length && isSigungu(tokens[index])) {
			sigungu = cleanToken(tokens[index]);
			index++;
			if (PROVINCES.contains(sido)
				&& sigungu.endsWith("시")
				&& index < tokens.length
				&& cleanToken(tokens[index]).endsWith("구")) {
				sigungu = sigungu + " " + cleanToken(tokens[index]);
				index++;
			}
		}
		if (index < tokens.length && isEupMyeonDong(tokens[index])) {
			eupMyeonDong = cleanToken(tokens[index]);
		}
		return RegionContext.korea(sido, sigungu, eupMyeonDong, null);
	}

	private static Map.Entry<String, String> entry(String alias, String canonicalName) {
		return Map.entry(alias, canonicalName);
	}

	private static boolean isSigungu(String token) {
		String cleaned = cleanToken(token);
		return cleaned.endsWith("시") || cleaned.endsWith("군") || cleaned.endsWith("구");
	}

	private static boolean isEupMyeonDong(String token) {
		String cleaned = cleanToken(token);
		return cleaned.endsWith("읍")
			|| cleaned.endsWith("면")
			|| cleaned.endsWith("동")
			|| NUMBERED_LEGAL_DONG.matcher(cleaned).matches();
	}

	private static String cleanToken(String token) {
		return token == null ? "" : token.replaceAll("^[,]+|[,]+$", "");
	}
}
