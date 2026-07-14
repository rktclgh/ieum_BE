package shinhan.fibri.ieum.common.config;

import java.util.Locale;
import org.hibernate.dialect.PostgreSQLDialect;

/**
 * Postgres native enum 캐스팅 타입명을 자바 enum 클래스명 → snake_case 로 변환하는 Dialect.
 *
 * <p>기본 {@link PostgreSQLDialect}는 {@link org.hibernate.dialect.Dialect#getEnumTypeDeclaration(Class)}에서
 * 자바 enum 클래스명을 그대로 캐스팅 타입으로 쓴다. 예: {@code FriendshipStatus} → SQL {@code 'accepted'::FriendshipStatus}.
 * Postgres는 따옴표 없는 식별자를 소문자로 접으므로({@code friendshipstatus}) snake_case 타입({@code friendship_status})과
 * 이름이 어긋나 {@code 42704 type "friendshipstatus" does not exist} 로 실패한다.
 * (JPQL 안에 enum 리터럴이 있는 쿼리에서만 캐스팅이 렌더되므로 friendship 조회에서 처음 드러났다.)
 *
 * <p>이 Dialect는 캐스팅 타입명을 스키마(SSOT)의 snake_case 타입명과 일치하도록 변환해 모든 enum에서 문제를 없앤다.
 * 스키마·데이터는 그대로 두고 Hibernate가 올바른 타입명을 렌더하게 만드는 코드-only 해법이다.
 */
public class SnakeCasePostgreSQLDialect extends PostgreSQLDialect {

	@Override
	public String getEnumTypeDeclaration(Class<? extends Enum<?>> enumType) {
		Class<?> actualEnumType = enumType.isEnum() ? enumType : enumType.getSuperclass();
		return toSnakeCase(actualEnumType.getSimpleName());
	}

	/**
	 * PascalCase 클래스명을 snake_case 로 변환한다.
	 * 예: {@code FriendshipStatus → friendship_status}, {@code GenderType → gender_type}.
	 * 연속 대문자도 처리: {@code AIVerdict → ai_verdict}.
	 */
	static String toSnakeCase(String className) {
		return className
			.replaceAll("([\\p{Lu}]+)([\\p{Lu}]\\p{Ll})", "$1_$2")
			.replaceAll("([\\p{Ll}\\d])(\\p{Lu})", "$1_$2")
			.toLowerCase(Locale.ROOT);
	}
}
