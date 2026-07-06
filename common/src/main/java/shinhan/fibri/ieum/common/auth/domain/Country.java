package shinhan.fibri.ieum.common.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "countries")
public class Country {

	@Id
	@Column(length = 2)
	private String code;

	@Column(name = "name_ko", nullable = false, length = 100)
	private String nameKo;

	@Column(name = "name_en", nullable = false, length = 100)
	private String nameEn;

	@Column(name = "is_active", nullable = false)
	private boolean isActive;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	protected Country() {
	}

	private Country(String code, String nameKo, String nameEn, boolean active) {
		this.code = Objects.requireNonNull(code, "code must not be null");
		this.nameKo = Objects.requireNonNull(nameKo, "nameKo must not be null");
		this.nameEn = Objects.requireNonNull(nameEn, "nameEn must not be null");
		this.isActive = active;
		this.createdAt = OffsetDateTime.now();
	}

	public static Country active(String code, String nameKo, String nameEn) {
		return new Country(code, nameKo, nameEn, true);
	}

	public static Country inactive(String code, String nameKo, String nameEn) {
		return new Country(code, nameKo, nameEn, false);
	}

	public String getCode() {
		return code;
	}

	public String getNameKo() {
		return nameKo;
	}

	public String getNameEn() {
		return nameEn;
	}

	public boolean isActive() {
		return isActive;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
