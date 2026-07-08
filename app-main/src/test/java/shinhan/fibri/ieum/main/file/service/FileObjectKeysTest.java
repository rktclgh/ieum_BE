package shinhan.fibri.ieum.main.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;

class FileObjectKeysTest {

	@Test
	void buildsTmpAndFinalOriginKeysFromContentType() {
		UUID fileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

		assertThat(FileObjectKeys.tmpOriginKey("tmp", 42L, "meeting", fileId, "image/jpeg"))
			.isEqualTo("tmp/42/meeting/11111111-1111-1111-1111-111111111111/original.jpg");
		assertThat(FileObjectKeys.finalOriginKey("final", 42L, "meeting", fileId, "image/png"))
			.isEqualTo("final/42/meeting/11111111-1111-1111-1111-111111111111/original.png");
	}

	@Test
	void promotesTmpOriginKeyToFinalPrefix() {
		String tmpKey = "tmp/42/meeting/11111111-1111-1111-1111-111111111111/original.jpg";

		assertThat(FileObjectKeys.promoteTmpOriginKey("tmp", "final", tmpKey))
			.isEqualTo("final/42/meeting/11111111-1111-1111-1111-111111111111/original.jpg");
	}

	@Test
	void derivesVariantKeysFromFinalOriginKey() {
		String originKey = "final/42/meeting/11111111-1111-1111-1111-111111111111/original.jpg";

		assertThat(FileObjectKeys.variantKey(originKey, FileVariant.ORIGIN)).isEqualTo(originKey);
		assertThat(FileObjectKeys.variantKey(originKey, FileVariant.DISPLAY))
			.isEqualTo("final/42/meeting/11111111-1111-1111-1111-111111111111/display.webp");
		assertThat(FileObjectKeys.variantKey(originKey, FileVariant.THUMB))
			.isEqualTo("final/42/meeting/11111111-1111-1111-1111-111111111111/thumb.webp");
	}

	@Test
	void rejectsUnsupportedContentType() {
		UUID fileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

		assertThatThrownBy(() -> FileObjectKeys.tmpOriginKey("tmp", 42L, "meeting", fileId, "image/gif"))
			.isInstanceOf(InvalidFileRequestException.class);
	}

	@Test
	void rejectsInvalidPurpose() {
		UUID fileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

		assertThatThrownBy(() -> FileObjectKeys.tmpOriginKey("tmp", 42L, "../meeting", fileId, "image/jpeg"))
			.isInstanceOf(InvalidFileRequestException.class);
	}

	@Test
	void rejectsUnknownPurpose() {
		UUID fileId = UUID.fromString("11111111-1111-1111-1111-111111111111");

		assertThatThrownBy(() -> FileObjectKeys.tmpOriginKey("tmp", 42L, "banana", fileId, "image/jpeg"))
			.isInstanceOf(InvalidFileRequestException.class);
	}
}
