package shinhan.fibri.ieum.main.pin.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(name = "pins")
@SQLRestriction("deleted_at IS NULL")
public class Pin {

	// Read model only: location is maintained by question/meeting creation SQL, not JpaRepository#save.
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "pin_id")
	private Long id;

	@Column(name = "author_id", nullable = false)
	private Long authorId;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "pin_type", nullable = false, columnDefinition = "varchar(30)")
	private PinType pinType;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;

	protected Pin() {
	}
}
