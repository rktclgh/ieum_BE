package shinhan.fibri.ieum.main.answer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.main.answer.domain.Answer;

public interface AnswerRepository extends JpaRepository<Answer, Long> {
}
