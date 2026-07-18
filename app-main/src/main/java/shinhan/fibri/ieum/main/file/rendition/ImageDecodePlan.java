package shinhan.fibri.ieum.main.file.rendition;

import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;

record ImageDecodePlan(int subsampling) {

	static ImageDecodePlan forSource(
		int width,
		int height,
		int displayMaxPx,
		int maxSourceDimension,
		long maxSourcePixels
	) {
		if (width <= 0 || height <= 0 || displayMaxPx <= 0 || maxSourceDimension <= 0 || maxSourcePixels <= 0) {
			throw new InvalidFileRequestException("Image dimensions are invalid");
		}

		int maxDimension = Math.max(width, height);
		if (maxDimension > maxSourceDimension) {
			throw new InvalidFileRequestException("Image exceeds the maximum allowed dimension");
		}

		long sourcePixels = (long) width * height;
		if (sourcePixels > maxSourcePixels) {
			throw new InvalidFileRequestException("Image exceeds the maximum allowed pixel count");
		}

		int subsampling = (int) Math.max(1L, (maxDimension + (long) displayMaxPx - 1L) / displayMaxPx);
		return new ImageDecodePlan(subsampling);
	}
}
