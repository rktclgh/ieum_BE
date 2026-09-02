package shinhan.fibri.ieum.main.ai.outbox.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;

/**
 * 이 태스크(Task 3)에서는 저장만 한다. 클레임 쿼리(FOR UPDATE SKIP LOCKED)는 Task 4에서
 * 네이티브 SQL로 추가된다(spec.md §7.2).
 */
public interface AiJobOutboxRepository extends JpaRepository<AiJobOutbox, Long> {
}
