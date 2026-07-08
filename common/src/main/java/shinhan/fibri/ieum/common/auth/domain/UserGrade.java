package shinhan.fibri.ieum.common.auth.domain;

public enum UserGrade {
	bronze,
	silver,
	gold,
	platinum,
	diamond;

	public static UserGrade fromAcceptedCount(int acceptedCount) {
		if (acceptedCount >= 50) {
			return diamond;
		}
		if (acceptedCount >= 30) {
			return platinum;
		}
		if (acceptedCount >= 15) {
			return gold;
		}
		if (acceptedCount >= 5) {
			return silver;
		}
		return bronze;
	}
}
