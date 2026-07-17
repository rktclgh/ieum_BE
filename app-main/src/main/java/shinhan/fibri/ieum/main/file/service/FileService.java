package shinhan.fibri.ieum.main.file.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.file.dto.FileCompleteResponse;
import shinhan.fibri.ieum.main.file.dto.FilePresignRequest;
import shinhan.fibri.ieum.main.file.dto.FilePresignResponse;
import shinhan.fibri.ieum.main.file.dto.FileStreamResponse;
import shinhan.fibri.ieum.main.file.exception.FileNotFoundException;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;
import shinhan.fibri.ieum.main.file.rendition.FileRendition;
import shinhan.fibri.ieum.main.file.rendition.ImageRenditionGenerator;
import shinhan.fibri.ieum.main.file.storage.FileObjectMetadata;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.file.storage.StoredFileObject;
import shinhan.fibri.ieum.main.file.storage.StoredFileStream;

@Service
public class FileService {

	private static final Logger log = LoggerFactory.getLogger(FileService.class);

	private final FileRepository fileRepository;
	private final FileTransactionalOps transactionalOps;
	private final FileStorage storage;
	private final ImageRenditionGenerator renditionGenerator;
	private final FileProperties properties;

	public FileService(
		FileRepository fileRepository,
		FileTransactionalOps transactionalOps,
		FileStorage storage,
		ImageRenditionGenerator renditionGenerator,
		FileProperties properties
	) {
		this.fileRepository = fileRepository;
		this.transactionalOps = transactionalOps;
		this.storage = storage;
		this.renditionGenerator = renditionGenerator;
		this.properties = properties;
	}

	public FilePresignResponse createPresign(AuthenticatedUser principal, FilePresignRequest request) {
		String contentType = validateOriginContentType(request.contentType());
		Long sizeBytes = validateSizeBytes(request.sizeBytes());
		UUID fileId = UUID.randomUUID();
		String key = FileObjectKeys.tmpOriginKey(
			properties.tmpPrefix(),
			principal.userId(),
			request.purpose(),
			fileId,
			contentType
		);
		File file = fileRepository.save(File.pending(fileId, principal.userId(), key, contentType, sizeBytes));
		URI uploadUrl = storage.createPresignedPutUrl(file.getS3Key(), contentType, properties.presignTtl());

		return new FilePresignResponse(file.getFileId(), uploadUrl);
	}

	public FileCompleteResponse complete(AuthenticatedUser principal, UUID fileId) {
		File file = transactionalOps.loadOwned(fileId, principal.userId());
		if (file.isUploaded()) {
			return new FileCompleteResponse(file.getFileId());
		}

		FileObjectMetadata metadata = storage.head(file.getS3Key());
		String contentType = validateOriginContentType(metadata.contentType());
		Long sizeBytes = validateSizeBytes(metadata.sizeBytes());
		if (!contentType.equals(FileObjectKeys.normalize(file.getContentType()))) {
			throw new InvalidFileRequestException("Uploaded content type does not match presigned content type");
		}

		String tmpKey = file.getS3Key();
		String finalKey = FileObjectKeys.promoteTmpOriginKey(
			properties.tmpPrefix(),
			properties.finalPrefix(),
			tmpKey
		);
		StoredFileObject origin = readOrigin(tmpKey, contentType, sizeBytes);
		var renditions = renditionGenerator.generate(origin, properties);
		storage.put(finalKey, contentType, origin.bytes());
		for (FileRendition rendition : renditions) {
			storage.put(
				FileObjectKeys.variantKey(finalKey, rendition.variant()),
				rendition.contentType(),
				rendition.bytes()
			);
		}
		transactionalOps.finalizeUpload(file.getFileId(), finalKey, OffsetDateTime.now(), contentType, sizeBytes);
		deleteTmpLogOnly(tmpKey);

		return new FileCompleteResponse(file.getFileId());
	}

	public FileStreamResponse stream(AuthenticatedUser principal, UUID fileId, String variant) {
		File file = transactionalOps.loadUploaded(fileId);
		FileVariant fileVariant = FileVariant.from(variant);
		String key = FileObjectKeys.variantKey(file.getS3Key(), fileVariant);
		FileObjectMetadata metadata = storage.head(key);
		String contentType = validateStreamContentType(metadata.contentType());
		Long sizeBytes = validateStreamSizeBytes(metadata.sizeBytes());

		return new FileStreamResponse(contentType, sizeBytes, () -> storage.get(key).body());
	}

	private StoredFileObject readOrigin(String key, String contentType, Long sizeBytes) {
		StoredFileStream stream = storage.get(key);
		try (var body = stream.body()) {
			return new StoredFileObject(key, contentType, sizeBytes, body.readAllBytes());
		} catch (IOException exception) {
			throw new UncheckedIOException("Failed to read uploaded file", exception);
		}
	}

	private String validateOriginContentType(String contentType) {
		String normalized = FileObjectKeys.normalize(contentType);
		if (!FileObjectKeys.isSupportedOriginContentType(normalized)) {
			throw new InvalidFileRequestException("Only jpeg and png images are supported");
		}
		return normalized;
	}

	private Long validateSizeBytes(Long sizeBytes) {
		if (sizeBytes == null || sizeBytes <= 0) {
			throw new InvalidFileRequestException("sizeBytes must be positive");
		}
		if (sizeBytes > properties.maxSizeBytes()) {
			throw new InvalidFileRequestException("File is too large");
		}
		return sizeBytes;
	}

	private String validateStreamContentType(String contentType) {
		String normalized = FileObjectKeys.normalize(contentType);
		if (!"image/jpeg".equals(normalized) && !"image/png".equals(normalized) && !"image/webp".equals(normalized)) {
			throw new InvalidFileRequestException("Only jpeg, png, and webp images can be streamed");
		}
		return normalized;
	}

	private Long validateStreamSizeBytes(Long sizeBytes) {
		if (sizeBytes == null || sizeBytes <= 0) {
			throw new InvalidFileRequestException("sizeBytes must be positive");
		}
		return sizeBytes;
	}

	private void deleteTmpLogOnly(String tmpKey) {
		try {
			storage.delete(tmpKey);
		} catch (RuntimeException exception) {
			log.warn("Failed to delete tmp file after upload completion. tmpKey={}", tmpKey, exception);
		}
	}
}
