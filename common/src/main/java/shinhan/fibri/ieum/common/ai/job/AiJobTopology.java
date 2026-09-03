package shinhan.fibri.ieum.common.ai.job;

/**
 * app-main / app-ai 가 공유하는 RabbitMQ 토폴로지 상수.
 * Spring/AMQP 타입에 의존하지 않는다 — common 모듈은 브로커 클라이언트를 모른다.
 */
public final class AiJobTopology {

	// --- exchange ---
	public static final String EXCHANGE_JOBS = "ieum.ai.jobs";
	public static final String EXCHANGE_RESULTS = "ieum.ai.results";
	public static final String EXCHANGE_RETRY = "ieum.ai.retry";
	public static final String EXCHANGE_DLX = "ieum.ai.dlx";

	// --- queue: question-answer dispatch ---
	public static final String QUEUE_QUESTION_ANSWER_DISPATCH = "ieum.ai.question-answer.dispatch";
	public static final String QUEUE_QUESTION_ANSWER_DISPATCH_RETRY = "ieum.ai.question-answer.dispatch.retry";
	public static final String QUEUE_QUESTION_ANSWER_DISPATCH_DLQ = "ieum.ai.question-answer.dispatch.dlq";

	// --- queue: accepted-answer knowledge ingest ---
	public static final String QUEUE_ACCEPTED_ANSWER_INGEST = "ieum.ai.accepted-answer.ingest";
	public static final String QUEUE_ACCEPTED_ANSWER_INGEST_RETRY = "ieum.ai.accepted-answer.ingest.retry";
	public static final String QUEUE_ACCEPTED_ANSWER_INGEST_DLQ = "ieum.ai.accepted-answer.ingest.dlq";

	// --- queue: question-answer completed (result) ---
	public static final String QUEUE_QUESTION_ANSWER_COMPLETED = "ieum.main.question-answer.completed";
	public static final String QUEUE_QUESTION_ANSWER_COMPLETED_RETRY = "ieum.main.question-answer.completed.retry";
	public static final String QUEUE_QUESTION_ANSWER_COMPLETED_DLQ = "ieum.main.question-answer.completed.dlq";

	// --- routing key (primary publish keys) ---
	public static final String ROUTING_KEY_QUESTION_ANSWER_DISPATCH = "ai.question-answer.dispatch";
	public static final String ROUTING_KEY_ACCEPTED_ANSWER_INGEST = "ai.accepted-answer.ingest";
	public static final String ROUTING_KEY_QUESTION_ANSWER_COMPLETED = "ai.question-answer.completed";

	// --- retry / delivery ---
	public static final int MAX_DELIVERY_ATTEMPTS = 5;
	public static final int RETRY_TTL_MS = 30_000;

	// --- schema / consumer ---
	public static final int SCHEMA_VERSION = 1;
	public static final int PREFETCH = 1;

	private AiJobTopology() {
	}
}
