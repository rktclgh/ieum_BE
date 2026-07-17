package shinhan.fibri.ieum.main.admin.stats.dto;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

public record StatsOverviewRequest(
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
	String bucket
) {
}
