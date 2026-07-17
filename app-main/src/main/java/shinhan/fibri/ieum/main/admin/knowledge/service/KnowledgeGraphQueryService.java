package shinhan.fibri.ieum.main.admin.knowledge.service;

import java.util.LinkedHashMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeGraphRequest;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeGraphResponse;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeGraphAdminRepository;
import shinhan.fibri.ieum.main.admin.knowledge.repository.JdbcKnowledgeGraphAdminRepository.GraphRelationRow;

@Service
@RequiredArgsConstructor
public class KnowledgeGraphQueryService {

	private final JdbcKnowledgeGraphAdminRepository repository;

	public AdminKnowledgeGraphResponse graph(AdminKnowledgeGraphRequest request) {
		int limit = request.limit();
		List<GraphRelationRow> rows = repository.findGraphRelations(
			request.query(),
			request.focus(),
			request.predicate(),
			limit + 1
		);
		boolean truncated = rows.size() > limit;
		List<GraphRelationRow> visibleRows = truncated ? rows.subList(0, limit) : rows;
		LinkedHashMap<String, NodeAccumulator> nodes = new LinkedHashMap<>();
		List<AdminKnowledgeGraphResponse.Edge> edges = visibleRows.stream()
			.map(row -> toEdge(row, nodes))
			.toList();
		return new AdminKnowledgeGraphResponse(
			nodes.values().stream()
				.map(NodeAccumulator::toNode)
				.toList(),
			edges,
			truncated
		);
	}

	private AdminKnowledgeGraphResponse.Edge toEdge(
		GraphRelationRow row,
		LinkedHashMap<String, NodeAccumulator> nodes
	) {
		nodes.computeIfAbsent(row.subject(), NodeAccumulator::new).increment();
		if (!row.subject().equals(row.object())) {
			nodes.computeIfAbsent(row.object(), NodeAccumulator::new).increment();
		}
		return new AdminKnowledgeGraphResponse.Edge(
			row.relationId(),
			row.subject(),
			row.object(),
			row.predicate(),
			row.confidence(),
			row.sourceId(),
			row.evidenceChunkId(),
			row.sourceDisplayName(),
			row.evidencePreview(),
			row.createdAt()
		);
	}

	private static final class NodeAccumulator {

		private final String term;
		private int degree;

		private NodeAccumulator(String term) {
			this.term = term;
		}

		private void increment() {
			degree++;
		}

		private AdminKnowledgeGraphResponse.Node toNode() {
			return new AdminKnowledgeGraphResponse.Node(term, term, degree);
		}
	}
}
