package shinhan.fibri.ieum.main.ai.outbox.service;

import java.time.Duration;

/**
 * relay 동작 파라미터. spec.md §7.1의 {@code app.ai.outbox.*}.
 *
 * <p>폴링·복구·보존 <b>주기</b>는 여기 없다 — {@code @Scheduled}가 프로퍼티 문자열을 직접 읽는다.
 * 이 레코드에는 relay 로직이 실제로 계산에 쓰는 값만 담는다.
 *
 * @param maxAttempts 이 횟수 이상 시도한 row는 {@code dead}로 간다. DB CHECK 상한(20)을 넘길 수 없다.
 */
public record AiJobOutboxProperties(
	String workerId,
	Duration lease,
	int maxAttempts,
	int batchSize,
	Duration confirmTimeout,
	int retentionDays,
	long backlogWarnThreshold
) {

	/**
	 * {@code confirmTimeout × batchSize}가 {@code lease}에 등호로 맞닿아 있으면, 배치 마지막 row의
	 * confirm이 lease 만료와 같은 순간 도착할 수 있다 — 그 confirm을 정산({@code markPublished}
	 * 커밋)하는 사이에 lease 복구 스케줄이 먼저 돌아 같은 row를 회수해가면, 뒤늦은 정산이 펜싱에
	 * 막혀 버려지고 이미 발행된 job이 중복 재발행된다. DB 정산 커밋 왕복 시간과 relay 인스턴스 간
	 * 시계 스큐를 흡수하도록 등호 경계에도 이만큼의 여유를 강제한다(CodeRabbit 재리뷰 finding 1).
	 */
	static final Duration SETTLEMENT_HEADROOM = Duration.ofSeconds(30);

	public AiJobOutboxProperties {
		if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
			throw new IllegalArgumentException("workerId must contain 1 to 120 characters");
		}
		if (lease == null || lease.isZero() || lease.isNegative()) {
			throw new IllegalArgumentException("lease must be positive");
		}
		if (maxAttempts < 1 || maxAttempts > 20) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and 20");
		}
		if (batchSize < 1 || batchSize > 500) {
			throw new IllegalArgumentException("batchSize must be between 1 and 500");
		}
		if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
			throw new IllegalArgumentException("confirmTimeout must be positive");
		}
		Duration worstCaseWithHeadroom = confirmTimeout.multipliedBy(batchSize).plus(SETTLEMENT_HEADROOM);
		if (worstCaseWithHeadroom.compareTo(lease) > 0) {
			// 클레임한 배치의 마지막 row는 앞선 (batchSize-1)개가 전부 confirmTimeout 을 다 채우는
			// 최악의 경우 그만큼 늦게 처리된다. 거기에 SETTLEMENT_HEADROOM(DB 정산 커밋 왕복 +
			// 시계 스큐 여유)까지 더한 총 시간이 lease 를 넘으면(등호 포함) 처리 도중 lease 가 만료돼
			// (1) 뒤늦은 confirm 이 펜싱에 막혀 버려지고 (2) lease 복구가 같은 row 를 재발행 대상으로
			// 되돌려 중복 발행을 일으킨다.
			throw new IllegalArgumentException(
				"confirmTimeout (%s) x batchSize (%d) + settlement headroom (%s) = %s must not exceed lease (%s)"
					.formatted(confirmTimeout, batchSize, SETTLEMENT_HEADROOM, worstCaseWithHeadroom, lease)
			);
		}
		if (retentionDays < 1) {
			throw new IllegalArgumentException("retentionDays must be at least 1");
		}
		if (backlogWarnThreshold < 1) {
			throw new IllegalArgumentException("backlogWarnThreshold must be at least 1");
		}
	}
}
