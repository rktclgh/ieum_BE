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
import shinhan.fibri.ieum.main.ai.knowledge.dispatch.AcceptedAnswerKnowledgeJobDispatchClient;
import shinhan.fibri.ieum.main.ai.knowledge.dispatch.AcceptedAnswerKnowledgeJobDispatchListener;

class AcceptedAnswerKnowledgeDispatchConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(AcceptedAnswerKnowledgeDispatchConfig.class);

	@Test
	void keepsAcceptedAnswerDispatchDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(AcceptedAnswerKnowledgeDispatchProperties.class);
			assertThat(context).doesNotHaveBean(AcceptedAnswerKnowledgeJobDispatchClient.class);
			assertThat(context).doesNotHaveBean(AcceptedAnswerKnowledgeJobDispatchListener.class);
		});
	}

	@Test
	void createsADedicatedBoundedAbortPolicyExecutorWhenEnabled() {
		contextRunner
			.withPropertyValues(
				"app.ai.accepted-answer-dispatch.enabled=true",
				"app.ai.accepted-answer-dispatch.base-url=http://10.0.20.15:8081",
				"app.ai.accepted-answer-dispatch.allowed-hosts=10.0.20.15"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(AcceptedAnswerKnowledgeDispatchProperties.class);
				assertThat(context).hasSingleBean(AcceptedAnswerKnowledgeJobDispatchClient.class);
				assertThat(context).hasSingleBean(AcceptedAnswerKnowledgeJobDispatchListener.class);

				ThreadPoolTaskExecutor executor = context.getBean(
					"acceptedAnswerKnowledgeDispatchTaskExecutor",
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
		server.createContext("/ai/v1/internal/accepted-answer-jobs/42/dispatch", exchange -> {
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
			AcceptedAnswerKnowledgeJobDispatchClient client = new AcceptedAnswerKnowledgeDispatchConfig()
				.acceptedAnswerKnowledgeJobDispatchClient(new AcceptedAnswerKnowledgeDispatchProperties(
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
