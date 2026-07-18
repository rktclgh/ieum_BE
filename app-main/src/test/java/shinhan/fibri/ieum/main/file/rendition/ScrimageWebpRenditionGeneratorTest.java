package shinhan.fibri.ieum.main.file.rendition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sksamuel.scrimage.ImmutableImage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;
import shinhan.fibri.ieum.main.file.service.FileProperties;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.StoredFileObject;

class ScrimageWebpRenditionGeneratorTest {

	private final FileProperties properties = new FileProperties(
			"tmp",
			"final",
			Duration.ofMinutes(15),
			10_485_760L,
			50_000_000L,
			16_384,
			1280,
			320,
			80
	);

	@Test
	void generatesDisplayAndThumbWebpRenditions() throws Exception {
		byte[] jpegBytes = jpegBytes();
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.jpg",
				"image/jpeg",
				(long) jpegBytes.length,
				jpegBytes
		);

		List<FileRendition> renditions = new ScrimageWebpRenditionGenerator().generate(origin, properties);

		assertThat(renditions).extracting(FileRendition::variant)
			.containsExactly(FileVariant.DISPLAY, FileVariant.THUMB);
		assertThat(renditions).allSatisfy(rendition -> {
			assertThat(rendition.contentType()).isEqualTo("image/webp");
			assertThat(rendition.bytes()).isNotEmpty();
		});
	}

	@Test
	void rejectsInvalidImageBytesAsInvalidFileRequest() {
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.png",
				"image/png",
				3L,
				new byte[] {1, 2, 3}
		);

		assertThatThrownBy(() -> new ScrimageWebpRenditionGenerator().generate(origin, properties))
			.isInstanceOf(InvalidFileRequestException.class)
			.hasMessage("Image bytes could not be rendered");
	}

	@Test
	void generatesRenditionsFromPngThatRequiresSourceSubsampling() throws Exception {
		byte[] pngBytes = pngBytes(1281, 720);
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.png",
				"image/png",
				(long) pngBytes.length,
				pngBytes
		);

		List<FileRendition> renditions = new ScrimageWebpRenditionGenerator().generate(origin, properties);

		assertThat(renditions).allSatisfy(rendition -> {
			assertThat(rendition.contentType()).isEqualTo("image/webp");
			assertThat(rendition.bytes()).isNotEmpty();
		});
	}

	@Test
	void preservesExifOrientationBeforeGeneratingWebpRenditions() throws Exception {
		byte[] jpegBytes = jpegBytesWithExifOrientation(6, 4, 2);
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.jpg",
				"image/jpeg",
				(long) jpegBytes.length,
				jpegBytes
		);

		List<FileRendition> renditions = new ScrimageWebpRenditionGenerator().generate(origin, properties);
		FileRendition display = renditions.stream()
			.filter(rendition -> rendition.variant() == FileVariant.DISPLAY)
			.findFirst()
			.orElseThrow();

		ImmutableImage renderedImage = ImmutableImage.loader().fromBytes(display.bytes());

		assertThat(renderedImage.width).isEqualTo(2);
		assertThat(renderedImage.height).isEqualTo(4);
	}

	@Test
	void rejectsUnsupportedImageFormat() throws Exception {
		byte[] gifBytes = gifBytes();
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.png",
				"image/png",
				(long) gifBytes.length,
				gifBytes
		);

		assertThatThrownBy(() -> new ScrimageWebpRenditionGenerator().generate(origin, properties))
			.isInstanceOf(InvalidFileRequestException.class)
			.hasMessage("Only jpeg and png images are supported");
	}

	@Test
	void rejectsSupportedImageFormatWhoseDeclaredContentTypeDoesNotMatch() throws Exception {
		byte[] jpegBytes = jpegBytes();
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.png",
				"image/png",
				(long) jpegBytes.length,
				jpegBytes
		);

		assertThatThrownBy(() -> new ScrimageWebpRenditionGenerator().generate(origin, properties))
			.isInstanceOf(InvalidFileRequestException.class)
			.hasMessage("Uploaded image content does not match content type");
	}

	@Test
	void rejectsSourceImageAboveConfiguredPixelLimitBeforeDecoding() throws Exception {
		byte[] pngBytes = pngBytes();
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.png",
				"image/png",
				(long) pngBytes.length,
				pngBytes
		);
		FileProperties restrictedProperties = new FileProperties(
				"tmp",
				"final",
			Duration.ofMinutes(15),
			10_485_760L,
			15L,
			16_384,
			1280,
				320,
				80
		);

		assertThatThrownBy(() -> new ScrimageWebpRenditionGenerator().generate(origin, restrictedProperties))
			.isInstanceOf(InvalidFileRequestException.class)
			.hasMessage("Image exceeds the maximum allowed pixel count");
	}

	private byte[] pngBytes() throws Exception {
		return pngBytes(4, 4);
	}

	private byte[] pngBytes(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, y, Color.BLUE.getRGB());
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private byte[] gifBytes() throws Exception {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "gif", out);
		return out.toByteArray();
	}

	private byte[] jpegBytes() throws Exception {
		return jpegBytes(4, 4);
	}

	private byte[] jpegBytes(int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private byte[] jpegBytesWithExifOrientation(int orientation, int width, int height) throws Exception {
		byte[] jpegBytes = jpegBytes(width, height);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpegBytes, 0, 2);
		out.write(new byte[] {
				(byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
				'E', 'x', 'i', 'f', 0x00, 0x00,
				'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
				0x01, 0x00,
				0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,
				(byte) orientation, 0x00, 0x00, 0x00,
				0x00, 0x00, 0x00, 0x00
		});
		out.write(jpegBytes, 2, jpegBytes.length - 2);
		return out.toByteArray();
	}
}
