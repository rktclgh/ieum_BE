package shinhan.fibri.ieum.main.answer.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
import shinhan.fibri.ieum.main.answer.exception.SelfAcceptanceNotAllowedException;
import shinhan.fibri.ieum.main.answer.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.answer.repository.AnswerRepository;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;

@Service
@RequiredArgsConstructor
public class AnswerService {

	private final QuestionRepository questionRepository;
	private final AnswerRepository answerRepository;
	private final AnswerImageRepository answerImageRepository;
	private final FileRepository fileRepository;
	private final UserRepository userRepository;
	private final NotificationPublisher notificationPublisher;

	@Transactional(timeout = 30)
	public CreateAnswerResponse create(AuthenticatedUser principal, Long questionId, CreateAnswerRequest request) {
		Question question = questionRepository.findActiveByIdForShare(questionId)
			.orElseThrow(QuestionNotFoundException::new);
		String content = requireContentOrImages(request);
		List<UUID> imageFileIds = normalizeImageFileIds(request.imageFileIds());
		List<File> files = validateImages(imageFileIds, principal.userId());

		Answer answer = answerRepository.save(Answer.createHuman(questionId, principal.userId(), content));
		List<AnswerImage> images = new ArrayList<>();
		for (int index = 0; index < files.size(); index++) {
			images.add(AnswerImage.link(answer.getId(), files.get(index).getFileId(), index));
		}
		answerImageRepository.saveAll(images);
		if (!question.getAuthorId().equals(principal.userId())) {
			notificationPublisher.publishDurable(
				question.getAuthorId(),
				NotificationType.question,
				"새 답변",
				"회원님의 질문에 답변이 달렸어요",
				questionId,
				false
			);
		}
		return new CreateAnswerResponse(answer.getId());
	}

	@Transactional(timeout = 30)
	public void accept(AuthenticatedUser principal, Long answerId) {
		Answer answer = answerRepository.findById(answerId)
			.orElseThrow(AnswerNotFoundException::new);
		Question question = questionRepository.findByIdForUpdate(answer.getQuestionId())
			.orElseThrow(QuestionNotFoundException::new);
		if (!question.getAuthorId().equals(principal.userId())) {
			throw new QuestionForbiddenException();
		}
		if (!answer.isAi() && question.getAuthorId().equals(answer.getAuthorId())) {
			throw new SelfAcceptanceNotAllowedException();
		}
		if (question.isResolved()) {
			throw new QuestionAlreadyResolvedException();
		}

		answer.accept();
		question.markResolved();
		if (!answer.isAi()) {
			userRepository.findByIdForUpdate(answer.getAuthorId())
				.ifPresent(User::recordAcceptedAnswer);
			notificationPublisher.publishDurable(
				answer.getAuthorId(),
				NotificationType.question,
				"답변 채택",
				"회원님의 답변이 채택됐어요",
				question.getId()
			);
		}
	}

	// content와 imageFileIds는 각각 선택이지만 최소 하나는 있어야 한다(설계 보강 2026-07-03).
	// 앱 전체 컨벤션(QUESTION/FRIEND 등)과 통일해 422가 아닌 400 VALIDATION_FAILED로 응답한다.
	private String requireContentOrImages(CreateAnswerRequest request) {
		boolean hasContent = request.content() != null && !request.content().isBlank();
		boolean hasImages = request.imageFileIds() != null && !request.imageFileIds().isEmpty();
		if (!hasContent && !hasImages) {
			throw new InvalidAnswerRequestException(
				"VALIDATION_FAILED",
				"content",
				"content or imageFileIds is required"
			);
		}
		return request.content() == null ? "" : request.content();
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
		if (imageFileIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, File> filesById = fileRepository.findAllByFileIdInAndUploaderId(imageFileIds, userId)
			.stream()
			.collect(Collectors.toMap(File::getFileId, Function.identity()));
		return imageFileIds.stream()
			.map(imageFileId -> requireUploadedFile(filesById, imageFileId))
			.toList();
	}

	private File requireUploadedFile(Map<UUID, File> filesById, UUID imageFileId) {
		File file = filesById.get(imageFileId);
		if (file == null || !file.isUploaded()) {
			throw new InvalidAnswerRequestException("INVALID_IMAGE", "imageFileIds", "Invalid image");
		}
		return file;
	}
}
