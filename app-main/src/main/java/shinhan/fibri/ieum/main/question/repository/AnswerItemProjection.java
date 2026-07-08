package shinhan.fibri.ieum.main.question.repository;

import java.time.Instant;
import java.util.UUID;

public interface AnswerItemProjection {

	Long getAnswerId();

	boolean getAi();

	Long getAuthorId();

	String getAuthorNickname();

	UUID getAuthorProfileFileId();

	String getContent();

	boolean getAccepted();

	Instant getCreatedAt();
}
