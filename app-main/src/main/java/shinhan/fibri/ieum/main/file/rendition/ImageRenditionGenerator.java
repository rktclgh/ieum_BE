package shinhan.fibri.ieum.main.file.rendition;

import java.util.List;
import shinhan.fibri.ieum.main.file.service.FileProperties;
import shinhan.fibri.ieum.main.file.storage.StoredFileObject;

public interface ImageRenditionGenerator {

	List<FileRendition> generate(StoredFileObject origin, FileProperties properties);
}
