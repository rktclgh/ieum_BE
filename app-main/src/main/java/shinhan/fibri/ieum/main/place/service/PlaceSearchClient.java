package shinhan.fibri.ieum.main.place.service;

import java.util.List;

public interface PlaceSearchClient {

	List<PlaceSearchCandidate> search(String query);
}
