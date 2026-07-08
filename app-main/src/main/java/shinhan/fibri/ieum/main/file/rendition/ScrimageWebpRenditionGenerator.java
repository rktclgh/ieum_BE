package shinhan.fibri.ieum.main.file.rendition;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.webp.WebpWriter;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;
import shinhan.fibri.ieum.main.file.service.FileProperties;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.StoredFileObject;

@Component
public class ScrimageWebpRenditionGenerator implements ImageRenditionGenerator {

	@Override
	public List<FileRendition> generate(StoredFileObject origin, FileProperties properties) {
		try {
			ImmutableImage image = ImmutableImage.loader().fromBytes(origin.bytes());
			WebpWriter writer = WebpWriter.DEFAULT.withQ(properties.webpQuality());

			return List.of(
				new FileRendition(
					FileVariant.DISPLAY,
					"image/webp",
					image.bound(properties.displayMaxPx(), properties.displayMaxPx()).bytes(writer)
				),
				new FileRendition(
					FileVariant.THUMB,
					"image/webp",
					image.bound(properties.thumbMaxPx(), properties.thumbMaxPx()).bytes(writer)
				)
			);
		} catch (IOException | RuntimeException exception) {
			throw new InvalidFileRequestException("Image bytes could not be rendered");
		}
	}
}
