package shinhan.fibri.ieum.main.notification.presence;

import java.util.ArrayList;
import java.util.List;

final class GeoHashGrid {

	static final int PRECISION = 5;
	private static final int LONGITUDE_CELLS = 1 << 13;
	private static final int LATITUDE_CELLS = 1 << 12;
	private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();

	private GeoHashGrid() {
	}

	static String encode(double latitude, double longitude) {
		Cell cell = cell(latitude, longitude);
		return encodeCell(cell.longitudeCell(), cell.latitudeCell());
	}

	private static String encodeCell(int longitudeCell, int latitudeCell) {
		StringBuilder key = new StringBuilder(PRECISION);
		int bits = 0;
		int value = 0;
		for (int bit = 12; bit >= 0; bit--) {
			value = (value << 1) | ((longitudeCell >> bit) & 1);
			if (++bits == 5) { key.append(BASE32[value]); bits = 0; value = 0; }
			if (bit > 0) {
				value = (value << 1) | ((latitudeCell >> (bit - 1)) & 1);
				if (++bits == 5) { key.append(BASE32[value]); bits = 0; value = 0; }
			}
		}
		return key.toString();
	}

	static List<String> neighbors(double latitude, double longitude, int rings) {
		Cell center = cell(latitude, longitude);
		List<String> keys = new ArrayList<>((rings * 2 + 1) * (rings * 2 + 1));
		for (int latitudeOffset = -rings; latitudeOffset <= rings; latitudeOffset++) {
			for (int longitudeOffset = -rings; longitudeOffset <= rings; longitudeOffset++) {
				int x = center.longitudeCell() + longitudeOffset;
				int y = center.latitudeCell() + latitudeOffset;
				if (x >= 0 && x < LONGITUDE_CELLS && y >= 0 && y < LATITUDE_CELLS) {
					keys.add(encode(x, y));
				}
			}
		}
		return keys;
	}

	private static Cell cell(double latitude, double longitude) {
		if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
			throw new IllegalArgumentException("invalid latitude or longitude");
		}
		return new Cell(
			Math.min(LONGITUDE_CELLS - 1, (int) ((longitude + 180) / 360 * LONGITUDE_CELLS)),
			Math.min(LATITUDE_CELLS - 1, (int) ((latitude + 90) / 180 * LATITUDE_CELLS))
		);
	}

	private static String encode(int longitudeCell, int latitudeCell) {
		return encodeCell(longitudeCell, latitudeCell);
	}

	private record Cell(int longitudeCell, int latitudeCell) {
	}
}
