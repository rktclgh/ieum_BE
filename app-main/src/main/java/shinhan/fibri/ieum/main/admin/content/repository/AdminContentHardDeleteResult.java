package shinhan.fibri.ieum.main.admin.content.repository;

import java.util.List;

public record AdminContentHardDeleteResult(
	List<String> s3Keys
) {
	public int deletedFileCount() {
		return s3Keys.size();
	}
}
