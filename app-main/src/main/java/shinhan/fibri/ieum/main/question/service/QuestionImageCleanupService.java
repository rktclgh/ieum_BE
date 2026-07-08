package shinhan.fibri.ieum.main.question.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.file.service.FileObjectKeys;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.question.repository.AnswerImageRepository;
import shinhan.fibri.ieum.main.question.repository.QuestionImageRepository;

@Service
public class QuestionImageCleanupService {

	private static final Logger log = LoggerFactory.getLogger(QuestionImageCleanupService.class);

	private final QuestionImageRepository questionImageRepository;
	private final AnswerImageRepository answerImageRepository;
	private final UserRepository userRepository;
	private final FileRepository fileRepository;
	private final FileStorage fileStorage;
	private final TransactionTemplate cleanupTransaction;

	@Autowired
	public QuestionImageCleanupService(
		QuestionImageRepository questionImageRepository,
		AnswerImageRepository answerImageRepository,
		UserRepository userRepository,
		FileRepository fileRepository,
		FileStorage fileStorage,
		PlatformTransactionManager transactionManager
	) {
		this(
			questionImageRepository,
			answerImageRepository,
			userRepository,
			fileRepository,
			fileStorage,
			new TransactionTemplate(transactionManager)
		);
	}

	QuestionImageCleanupService(
		QuestionImageRepository questionImageRepository,
		AnswerImageRepository answerImageRepository,
		UserRepository userRepository,
		FileRepository fileRepository,
		FileStorage fileStorage,
		TransactionTemplate cleanupTransaction
	) {
		this.questionImageRepository = questionImageRepository;
		this.answerImageRepository = answerImageRepository;
		this.userRepository = userRepository;
		this.fileRepository = fileRepository;
		this.fileStorage = fileStorage;
		this.cleanupTransaction = cleanupTransaction;
		this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void cleanRemovedImagesAfterCommit(Collection<UUID> removedFileIds) {
		new LinkedHashSet<>(removedFileIds).forEach(fileId -> deleteFileRow(fileId).ifPresent(this::deleteS3ObjectsLogOnly));
	}

	private Optional<String> deleteFileRow(UUID fileId) {
		try {
			Optional<String> deletedKey = cleanupTransaction.execute(status -> {
				if (isStillReferenced(fileId)) {
					return Optional.empty();
				}
				return fileRepository.findById(fileId)
					.map(file -> {
						String s3Key = file.getS3Key();
						fileRepository.delete(file);
						return s3Key;
					});
			});
			return deletedKey == null ? Optional.empty() : deletedKey;
		} catch (RuntimeException exception) {
			log.warn("Failed to delete unreferenced question image file row. fileId={}", fileId, exception);
			return Optional.empty();
		}
	}

	private boolean isStillReferenced(UUID fileId) {
		return questionImageRepository.existsByFileId(fileId)
			|| answerImageRepository.existsByFileId(fileId)
			|| userRepository.existsByProfileFileIdAndDeletedAtIsNull(fileId);
	}

	private void deleteS3ObjectsLogOnly(String s3Key) {
		try {
			fileStorage.delete(s3Key);
			fileStorage.delete(FileObjectKeys.variantKey(s3Key, FileVariant.DISPLAY));
			fileStorage.delete(FileObjectKeys.variantKey(s3Key, FileVariant.THUMB));
		} catch (RuntimeException exception) {
			log.warn("Failed to delete unreferenced question image file objects. s3Key={}", s3Key, exception);
		}
	}
}
