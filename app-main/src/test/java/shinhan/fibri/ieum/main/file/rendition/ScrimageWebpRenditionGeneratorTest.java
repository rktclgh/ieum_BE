package shinhan.fibri.ieum.main.file.rendition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
			1280,
			320,
			80
	);

	@Test
	void generatesDisplayAndThumbWebpRenditions() throws Exception {
		StoredFileObject origin = new StoredFileObject(
				"final/42/file/original.png",
				"image/png",
				(long) pngBytes().length,
				pngBytes()
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

	private byte[] pngBytes() throws Exception {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				image.setRGB(x, y, Color.BLUE.getRGB());
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
