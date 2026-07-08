package shinhan.fibri.ieum.main.question.service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.main.pin.service.PinCursor;
import shinhan.fibri.ieum.main.question.domain.Question;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;
import shinhan.fibri.ieum.main.question.dto.AnswerItem;
import shinhan.fibri.ieum.main.question.dto.AuthorSummary;
import shinhan.fibri.ieum.main.question.dto.CursorPage;
import shinhan.fibri.ieum.main.question.dto.MyQuestionItem;
import shinhan.fibri.ieum.main.question.dto.QuestionCreateRequest;
import shinhan.fibri.ieum.main.question.dto.QuestionDetailResponse;
import shinhan.fibri.ieum.main.question.dto.QuestionUpdateRequest;
import shinhan.fibri.ieum.main.question.exception.InvalidQuestionRequestException;
import shinhan.fibri.ieum.main.question.exception.QuestionForbiddenException;
import shinhan.fibri.ieum.main.question.exception.QuestionNotFoundException;
import shinhan.fibri.ieum.main.question.repository.MyQuestionItemProjection;
import shinhan.fibri.ieum.main.question.repository.QuestionDetailProjection;
import shinhan.fibri.ieum.main.question.repository.QuestionImageRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionRepository;

@Service
@RequiredArgsConstructor
public class QuestionService {

	private static final String DISPLAY_URL_TEMPLATE = "/api/v1/files/%s?v=display";
	private static final String THUMB_URL_TEMPLATE = "/api/v1/files/%s?v=thumb";
	private static final String PROFILE_URL_TEMPLATE = "/api/v1/files/%s";

	private final QuestionRepository questionRepository;
	private final QuestionImageRepository questionImageRepository;
	private final FileRepository fileRepository;
	private final UserRepository userRepository;
	private final PinWriter pinWriter;
	private final QuestionImageCleanupService imageCleanupService;

	@Transactional
	public QuestionDetailResponse create(AuthenticatedUser principal, QuestionCreateRequest request) {
		List<UUID> imageFileIds = normalizeImageFileIds(request.imageFileIds());
		List<File> files = validateImages(imageFileIds, principal.userId());
		User author = userRepository.findByIdAndDeletedAtIsNull(principal.userId())
			.orElseThrow(() -> new InvalidQuestionRequestException("QUESTION_AUTHOR_NOT_FOUND", "author", "Author not found"));

		Long pinId = pinWriter.create(
			principal.userId(),
			PinType.question,
			request.location().latitude(),
			request.location().longitude()
		);
		Question question = questionRepository.save(Question.create(
			pinId,
			principal.userId(),
			request.title(),
			request.content()
		));

		List<QuestionImage> images = new ArrayList<>();
		for (int index = 0; index < files.size(); index++) {
			images.add(QuestionImage.link(question.getId(), files.get(index).getFileId(), index));
		}
		questionImageRepository.saveAll(images);

		return new QuestionDetailResponse(
			question.getId(),
			question.getTitle(),
			question.getContent(),
			question.isResolved(),
			new AuthorSummary(author.getId(), author.getNickname(), profileUrl(author.getProfileFileId())),
			imageFileIds.stream()
				.map(fileId -> DISPLAY_URL_TEMPLATE.formatted(fileId))
				.toList(),
			List.<AnswerItem>of()
		);
	}

	@Transactional(readOnly = true)
	public QuestionDetailResponse getDetail(Long questionId) {
		QuestionDetailProjection detail = questionRepository.findDetailByQuestionId(questionId)
			.orElseThrow(QuestionNotFoundException::new);
		List<String> imageUrls = questionImageRepository.findByQuestionIdOrderBySortOrderAsc(questionId)
			.stream()
			.map(QuestionImage::getFileId)
			.map(fileId -> DISPLAY_URL_TEMPLATE.formatted(fileId))
			.toList();
		return toDetailResponse(detail, imageUrls);
	}

	@Transactional(readOnly = true)
	public CursorPage<MyQuestionItem> listMine(AuthenticatedUser principal, String cursor, int size) {
		int requestedSize = Math.max(1, Math.min(size, 50));
		Long cursorId = PinCursor.decode(cursor);
		List<MyQuestionItemProjection> rows = cursorId == null
			? questionRepository.findMineFirstPage(principal.userId(), requestedSize + 1)
			: questionRepository.findMineAfterCursor(principal.userId(), cursorId, requestedSize + 1);
		String nextCursor = null;
		if (rows.size() > requestedSize) {
			nextCursor = PinCursor.encode(rows.get(requestedSize).getQuestionId());
			rows = rows.subList(0, requestedSize);
		}
		List<MyQuestionItem> items = rows.stream()
			.map(row -> new MyQuestionItem(
				row.getQuestionId(),
				row.getTitle(),
				row.getResolved(),
				row.getThumbnailFileId() == null ? null : THUMB_URL_TEMPLATE.formatted(row.getThumbnailFileId()),
				row.getAnswerCount(),
				row.getCreatedAt().atOffset(ZoneOffset.UTC)
			))
			.toList();
		return new CursorPage<>(items, nextCursor);
	}

	@Transactional
	public QuestionDetailResponse update(AuthenticatedUser principal, Long questionId, QuestionUpdateRequest request) {
		Question question = questionRepository.findByIdForUpdate(questionId)
			.orElseThrow(QuestionNotFoundException::new);
		if (!question.getAuthorId().equals(principal.userId())) {
			throw new QuestionForbiddenException();
		}

		question.update(request.title(), request.content());
		List<UUID> imageFileIds = request.imageFileIds() == null ? null : normalizeImageFileIds(request.imageFileIds());
		List<UUID> removedFileIds = List.of();
		if (imageFileIds != null) {
			List<QuestionImage> existingImages = questionImageRepository.findByQuestionIdOrderBySortOrderAsc(questionId);
			removedFileIds = existingImages.stream()
				.map(QuestionImage::getFileId)
				.filter(fileId -> !imageFileIds.contains(fileId))
				.toList();
			List<File> files = validateImages(imageFileIds, principal.userId());
			questionImageRepository.deleteByQuestionId(questionId);
			List<QuestionImage> newImages = new ArrayList<>();
			for (int index = 0; index < files.size(); index++) {
				newImages.add(QuestionImage.link(questionId, files.get(index).getFileId(), index));
			}
			questionImageRepository.saveAll(newImages);
		}

		if (!removedFileIds.isEmpty()) {
			scheduleRemovedImagesCleanupAfterCommit(removedFileIds);
		}
		User author = userRepository.findByIdAndDeletedAtIsNull(principal.userId())
			.orElseThrow(() -> new InvalidQuestionRequestException("QUESTION_AUTHOR_NOT_FOUND", "author", "Author not found"));
		List<String> imageUrls = imageFileIds == null
			? questionImageRepository.findByQuestionIdOrderBySortOrderAsc(questionId).stream()
				.map(QuestionImage::getFileId)
				.map(fileId -> DISPLAY_URL_TEMPLATE.formatted(fileId))
				.toList()
			: imageFileIds.stream().map(fileId -> DISPLAY_URL_TEMPLATE.formatted(fileId)).toList();
		return new QuestionDetailResponse(
			question.getId(),
			question.getTitle(),
			question.getContent(),
			question.isResolved(),
			new AuthorSummary(author.getId(), author.getNickname(), profileUrl(author.getProfileFileId())),
			imageUrls,
			List.<AnswerItem>of()
		);
	}

	private QuestionDetailResponse toDetailResponse(QuestionDetailProjection detail, List<String> imageUrls) {
		return new QuestionDetailResponse(
			detail.getQuestionId(),
			detail.getTitle(),
			detail.getContent(),
			detail.getResolved(),
			new AuthorSummary(
				detail.getAuthorId(),
				detail.getAuthorNickname(),
				profileUrl(detail.getAuthorProfileFileId())
			),
			imageUrls,
			List.<AnswerItem>of()
		);
	}

	private void scheduleRemovedImagesCleanupAfterCommit(List<UUID> removedFileIds) {
		List<UUID> cleanupFileIds = List.copyOf(removedFileIds);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			imageCleanupService.cleanRemovedImagesAfterCommit(cleanupFileIds);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				imageCleanupService.cleanRemovedImagesAfterCommit(cleanupFileIds);
			}
		});
	}

	private String profileUrl(UUID fileId) {
		return fileId == null ? null : PROFILE_URL_TEMPLATE.formatted(fileId);
	}

	private List<UUID> normalizeImageFileIds(List<UUID> imageFileIds) {
		if (imageFileIds == null) {
			return List.of();
		}
		HashSet<UUID> seen = new HashSet<>();
		for (UUID imageFileId : imageFileIds) {
			if (imageFileId == null || !seen.add(imageFileId)) {
				throw new InvalidQuestionRequestException("INVALID_IMAGE", "imageFileIds", "Invalid image");
			}
		}
		return List.copyOf(imageFileIds);
	}

	private List<File> validateImages(List<UUID> imageFileIds, Long userId) {
		List<File> files = new ArrayList<>();
		for (UUID imageFileId : imageFileIds) {
			File file = fileRepository.findByFileIdAndUploaderId(imageFileId, userId)
				.filter(File::isUploaded)
				.orElseThrow(() -> new InvalidQuestionRequestException("INVALID_IMAGE", "imageFileIds", "Invalid image"));
			files.add(file);
		}
		return files;
	}
}
