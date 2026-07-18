package shinhan.fibri.ieum.main.file.rendition;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.metadata.ImageMetadata;
import com.sksamuel.scrimage.metadata.OrientationTools;
import com.sksamuel.scrimage.webp.WebpWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;
import shinhan.fibri.ieum.main.file.service.FileProperties;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.StoredFileObject;

@Component
public class ScrimageWebpRenditionGenerator implements ImageRenditionGenerator {

	@Override
	public List<FileRendition> generate(StoredFileObject origin, FileProperties properties) {
		try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(origin.bytes()))) {
			ImageReader reader = findReader(input);
			try {
				reader.setInput(input, true, true);
				validateOriginFormat(reader, origin.contentType());
				ImageDecodePlan decodePlan = ImageDecodePlan.forSource(
					reader.getWidth(0),
					reader.getHeight(0),
					properties.displayMaxPx(),
					properties.maxSourceDimension(),
					properties.maxSourcePixels()
				);
				ImageReadParam readParam = reader.getDefaultReadParam();
				readParam.setSourceSubsampling(decodePlan.subsampling(), decodePlan.subsampling(), 0, 0);
				BufferedImage bufferedImage = reader.read(0, readParam);
				ImmutableImage displayImage = reorient(ImmutableImage.fromAwt(bufferedImage), origin.bytes())
					.bound(properties.displayMaxPx(), properties.displayMaxPx());
				WebpWriter writer = WebpWriter.DEFAULT.withQ(properties.webpQuality());

				return List.of(
					new FileRendition(
						FileVariant.DISPLAY,
						"image/webp",
						displayImage.bytes(writer)
					),
					new FileRendition(
						FileVariant.THUMB,
						"image/webp",
						displayImage.bound(properties.thumbMaxPx(), properties.thumbMaxPx()).bytes(writer)
					)
				);
			} finally {
				reader.dispose();
			}
		} catch (InvalidFileRequestException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw new InvalidFileRequestException("Image bytes could not be rendered");
		}
	}

	private ImageReader findReader(ImageInputStream input) {
		Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
		if (!readers.hasNext()) {
			throw new InvalidFileRequestException("Image bytes could not be rendered");
		}
		return readers.next();
	}

	private void validateOriginFormat(ImageReader reader, String declaredContentType) throws IOException {
		String actualContentType = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
			case "jpeg", "jpg" -> "image/jpeg";
			case "png" -> "image/png";
			default -> throw new InvalidFileRequestException("Only jpeg and png images are supported");
		};
		if (!actualContentType.equals(declaredContentType)) {
			throw new InvalidFileRequestException("Uploaded image content does not match content type");
		}
	}

	private ImmutableImage reorient(ImmutableImage image, byte[] sourceBytes) {
		try {
			return OrientationTools.reorient(image, ImageMetadata.fromBytes(sourceBytes));
		} catch (IOException exception) {
			return image;
		}
	}
}
