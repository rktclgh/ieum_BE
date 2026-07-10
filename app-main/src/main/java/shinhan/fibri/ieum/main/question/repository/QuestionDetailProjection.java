package shinhan.fibri.ieum.main.question.repository;

import java.util.UUID;

public interface QuestionDetailProjection {

	Long getQuestionId();

	String getTitle();

	String getContent();

	boolean getResolved();

	Long getAuthorId();

	String getAuthorNickname();

	UUID getAuthorProfileFileId();

	double getLatitude();

	double getLongitude();

	String getAddress();

	String getDetailAddress();

	String getLabel();
}
