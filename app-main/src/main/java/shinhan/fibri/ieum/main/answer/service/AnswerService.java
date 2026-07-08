package shinhan.fibri.ieum.main.answer.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerRequest;
import shinhan.fibri.ieum.main.answer.dto.CreateAnswerResponse;
import shinhan.fibri.ieum.main.answer.exception.AnswerNotFoundException;
import shinhan.fibri.ieum.main.answer.exception.InvalidAnswerRequestException;
import shinhan.fibri.ieum.main.answer.exception.QuestionAlreadyResolvedException;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;

@Service
@RequiredArgsConstructor
public class AnswerService {

	private final QuestionRepository questionRepository;
	private final AnswerRepository answerRepository;
	private final AnswerImageRepository answerImageRepository;
	private final FileRepository fileRepository;
	private final UserRepository userRepository;

	@Transactional
	public CreateAnswerResponse create(AuthenticatedUser principal, Long questionId, CreateAnswerRequest request) {
		if (!questionRepository.existsById(questionId)) {
			throw new QuestionNotFoundException();
		}
		List<UUID> imageFileIds = normalizeImageFileIds(request.imageFileIds());
		List<File> files = validateImages(imageFileIds, principal.userId());

		Answer answer = answerRepository.save(Answer.createHuman(questionId, principal.userId(), request.content()));
		List<AnswerImage> images = new ArrayList<>();
		for (int index = 0; index < files.size(); index++) {
			images.add(AnswerImage.link(answer.getId(), files.get(index).getFileId(), index));
		}
		answerImageRepository.saveAll(images);
		return new CreateAnswerResponse(answer.getId());
	}

	@Transactional
	public void accept(AuthenticatedUser principal, Long answerId) {
		Answer answer = answerRepository.findById(answerId)
			.orElseThrow(AnswerNotFoundException::new);
		Question question = questionRepository.findByIdForUpdate(answer.getQuestionId())
			.orElseThrow(QuestionNotFoundException::new);
		if (!question.getAuthorId().equals(principal.userId())) {
			throw new QuestionForbiddenException();
		}
		if (question.isResolved()) {
			throw new QuestionAlreadyResolvedException();
		}

		answer.accept();
		question.markResolved();
		if (!answer.isAi()) {
			userRepository.findByIdAndDeletedAtIsNull(answer.getAuthorId())
				.ifPresent(User::recordAcceptedAnswer);
		}
	}

	private List<UUID> normalizeImageFileIds(List<UUID> imageFileIds) {
		if (imageFileIds == null) {
			return List.of();
		}
		if (imageFileIds.size() > 10) {
			throw new InvalidAnswerRequestException("INVALID_IMAGE", "imageFileIds", "Invalid image");
		}
		HashSet<UUID> seen = new HashSet<>();
		for (UUID imageFileId : imageFileIds) {
			if (imageFileId == null || !seen.add(imageFileId)) {
				throw new InvalidAnswerRequestException("INVALID_IMAGE", "imageFileIds", "Invalid image");
			}
		}
		return List.copyOf(imageFileIds);
	}

	private List<File> validateImages(List<UUID> imageFileIds, Long userId) {
		List<File> files = new ArrayList<>();
		for (UUID imageFileId : imageFileIds) {
			File file = fileRepository.findByFileIdAndUploaderId(imageFileId, userId)
				.filter(File::isUploaded)
				.orElseThrow(() -> new InvalidAnswerRequestException("INVALID_IMAGE", "imageFileIds", "Invalid image"));
			files.add(file);
		}
		return files;
	}
}
