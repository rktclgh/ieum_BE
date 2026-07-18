package shinhan.fibri.ieum.main.file.rendition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;

class ImageDecodePlanTest {

	private static final int DISPLAY_MAX_PX = 1280;
	private static final int MAX_SOURCE_DIMENSION = 16_384;
	private static final long MAX_SOURCE_PIXELS = 50_000_000L;

	@Test
	void calculatesSourceSubsamplingBeforeRasterDecode() {
		ImageDecodePlan plan = ImageDecodePlan.forSource(
			4032,
			3024,
			DISPLAY_MAX_PX,
			MAX_SOURCE_DIMENSION,
			MAX_SOURCE_PIXELS
		);

		assertThat(plan.subsampling()).isEqualTo(3);
	}

	@Test
	void keepsImagesAtOrBelowDisplayLimitAtNativeResolution() {
		ImageDecodePlan plan = ImageDecodePlan.forSource(
			1280,
			720,
			DISPLAY_MAX_PX,
			MAX_SOURCE_DIMENSION,
			MAX_SOURCE_PIXELS
		);

		assertThat(plan.subsampling()).isOne();
	}

	@Test
	void rejectsSourcesAboveConfiguredPixelLimitBeforeDecode() {
		assertThatThrownBy(() -> ImageDecodePlan.forSource(
			10_000,
			6_000,
			DISPLAY_MAX_PX,
			MAX_SOURCE_DIMENSION,
			MAX_SOURCE_PIXELS
		))
			.isInstanceOf(InvalidFileRequestException.class)
			.hasMessage("Image exceeds the maximum allowed pixel count");
	}

	@Test
	void rejectsAnExtremeAspectRatioBeforePngDecoderAllocatesScanlineBuffers() {
		assertThatThrownBy(() -> ImageDecodePlan.forSource(
			16_385,
			1,
			DISPLAY_MAX_PX,
			MAX_SOURCE_DIMENSION,
			MAX_SOURCE_PIXELS
		))
			.isInstanceOf(InvalidFileRequestException.class)
			.hasMessage("Image exceeds the maximum allowed dimension");
	}
}
