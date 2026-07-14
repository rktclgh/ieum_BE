package shinhan.fibri.ieum.common.file.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "files")
public class File implements Persistable<UUID> {

	@Id
	@Column(name = "file_id", nullable = false, updatable = false)
	private UUID fileId;

	@Column(name = "uploader_id")
	private Long uploaderId;

	@Column(name = "s3_key", nullable = false, unique = true)
	private String s3Key;

	@Column(name = "content_type", length = 100)
	private String contentType;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	@Column(name = "uploaded_at")
	private OffsetDateTime uploadedAt;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Transient
	private boolean isNew = true;

	protected File() {
	}

	private File(UUID fileId, Long uploaderId, String s3Key, String contentType, Long sizeBytes) {
		this.fileId = Objects.requireNonNull(fileId, "fileId must not be null");
		this.uploaderId = uploaderId;
		this.s3Key = Objects.requireNonNull(s3Key, "s3Key must not be null");
		this.contentType = Objects.requireNonNull(contentType, "contentType must not be null");
		this.sizeBytes = Objects.requireNonNull(sizeBytes, "sizeBytes must not be null");
		this.createdAt = OffsetDateTime.now();
	}

	public static File pending(UUID fileId, Long uploaderId, String s3Key, String contentType, Long sizeBytes) {
		return new File(fileId, uploaderId, s3Key, contentType, sizeBytes);
	}

	public void markUploaded(OffsetDateTime uploadedAt, String contentType, Long sizeBytes) {
		this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt must not be null");
		this.contentType = Objects.requireNonNull(contentType, "contentType must not be null");
		this.sizeBytes = Objects.requireNonNull(sizeBytes, "sizeBytes must not be null");
	}

	public void promoteKey(String finalKey) {
		this.s3Key = Objects.requireNonNull(finalKey, "finalKey must not be null");
	}

	public boolean isUploaded() {
		return uploadedAt != null;
	}

	public boolean isOwnedBy(Long userId) {
		return Objects.equals(uploaderId, userId);
	}

	@Override
	public UUID getId() {
		return fileId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	public UUID getFileId() {
		return fileId;
	}

	public Long getUploaderId() {
		return uploaderId;
	}

	public String getS3Key() {
		return s3Key;
	}

	public String getContentType() {
		return contentType;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public OffsetDateTime getUploadedAt() {
		return uploadedAt;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
