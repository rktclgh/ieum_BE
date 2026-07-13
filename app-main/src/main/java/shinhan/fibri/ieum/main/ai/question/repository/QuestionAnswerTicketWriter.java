package shinhan.fibri.ieum.main.ai.question.repository;

public interface QuestionAnswerTicketWriter {

	void create(Long questionId);

	void requestCancellation(Long questionId);
}
