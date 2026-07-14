package shinhan.fibri.ieum.main.admin.report.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import shinhan.fibri.ieum.main.admin.report.exception.InvalidAdminReportCursorException;

public final class AdminReportCursor {

	private static final String VERSION = "v1";

	private AdminReportCursor() {
	}

	public static String encode(OffsetDateTime createdAt, Long reportId) {
		if (createdAt == null || reportId == null) {
			return null;
		}
		if (reportId <= 0) {
			throw new InvalidAdminReportCursorException();
		}
		Instant instant = createdAt.toInstant();
		String payload = "%s:%d:%d:%d".formatted(
			VERSION,
			instant.getEpochSecond(),
			instant.getNano(),
			reportId
		);
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
	}

	public static Position decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			return null;
		}
		try {
			String payload = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] fields = payload.split(":", -1);
			if (fields.length != 4 || !VERSION.equals(fields[0])) {
				throw new InvalidAdminReportCursorException();
			}
			long epochSecond = Long.parseLong(fields[1]);
			int nano = Integer.parseInt(fields[2]);
			long reportId = Long.parseLong(fields[3]);
			if (nano < 0 || nano > 999_999_999 || reportId <= 0) {
				throw new InvalidAdminReportCursorException();
			}
			return new Position(
				OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSecond, nano), ZoneOffset.UTC),
				reportId
			);
		} catch (InvalidAdminReportCursorException exception) {
			throw exception;
		} catch (IllegalArgumentException | DateTimeException exception) {
			throw new InvalidAdminReportCursorException();
		}
	}

	public record Position(OffsetDateTime createdAt, Long reportId) {
		public Position {
			if (createdAt == null || reportId == null || reportId <= 0) {
				throw new InvalidAdminReportCursorException();
			}
		}
	}
}
