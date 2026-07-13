package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.main.ai.question.dispatch.QuestionAnswerJobDispatchClient;
import shinhan.fibri.ieum.main.ai.question.dispatch.QuestionAnswerJobDispatchListener;

class QuestionAnswerDispatchConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(QuestionAnswerDispatchConfig.class);

	@Test
	void keepsQuestionAnswerDispatchDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(QuestionAnswerDispatchProperties.class);
			assertThat(context).doesNotHaveBean(QuestionAnswerJobDispatchClient.class);
			assertThat(context).doesNotHaveBean(QuestionAnswerJobDispatchListener.class);
		});
	}

	@Test
	void createsTheDedicatedBoundedAbortPolicyExecutorWhenEnabled() {
		contextRunner
			.withPropertyValues(
				"app.ai.question-answer-dispatch.enabled=true",
				"app.ai.question-answer-dispatch.base-url=http://10.0.20.15:8081",
				"app.ai.question-answer-dispatch.allowed-hosts=10.0.20.15"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionAnswerDispatchProperties.class);
				assertThat(context).hasSingleBean(QuestionAnswerJobDispatchClient.class);
				assertThat(context).hasSingleBean(QuestionAnswerJobDispatchListener.class);

				ThreadPoolTaskExecutor executor = context.getBean(
					"questionAnswerDispatchTaskExecutor",
					ThreadPoolTaskExecutor.class
				);
				assertThat(executor.getCorePoolSize()).isEqualTo(1);
				assertThat(executor.getMaxPoolSize()).isEqualTo(1);
				assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(32);
				assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
					.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
			});
	}

	@Test
	void doesNotFollowRedirectResponsesFromAppAi() throws Exception {
		AtomicInteger dispatchRequests = new AtomicInteger();
		AtomicInteger redirectedRequests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ai/v1/internal/question-answer-jobs/42/dispatch", exchange -> {
			dispatchRequests.incrementAndGet();
			exchange.getResponseHeaders().add("Location", "/redirect-target");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.createContext("/redirect-target", exchange -> {
			redirectedRequests.incrementAndGet();
			exchange.sendResponseHeaders(202, -1);
			exchange.close();
		});
		server.start();

		try {
			QuestionAnswerJobDispatchClient client = new QuestionAnswerDispatchConfig()
				.questionAnswerJobDispatchClient(new QuestionAnswerDispatchProperties(
					"http://127.0.0.1:" + server.getAddress().getPort(),
					"127.0.0.1",
					Duration.ofSeconds(2),
					Duration.ofSeconds(5)
				));

			assertThatThrownBy(() -> client.dispatch(42L))
				.isInstanceOf(org.springframework.web.client.RestClientResponseException.class)
				.satisfies(exception -> assertThat(
					((org.springframework.web.client.RestClientResponseException) exception).getStatusCode().value()
				).isEqualTo(302));
		}
		finally {
			server.stop(0);
		}

		assertThat(dispatchRequests).hasValue(1);
		assertThat(redirectedRequests).hasValue(0);
	}
}
