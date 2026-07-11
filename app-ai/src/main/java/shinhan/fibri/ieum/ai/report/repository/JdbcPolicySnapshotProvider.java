package shinhan.fibri.ieum.ai.report.repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.ai.report.domain.ReportEvidenceType;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyDecision;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyRule;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySeverity;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.service.PolicySnapshotProvider;

@Repository
public class JdbcPolicySnapshotProvider implements PolicySnapshotProvider {

	private final JdbcClient jdbc;

	public JdbcPolicySnapshotProvider(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ReportPolicySnapshot loadActiveSnapshot() {
		List<PolicyRow> rows = jdbc.sql("""
			SELECT rule_code, category, decision, severity, min_confidence,
			       evidence_types, priority, revision, content_hash
			FROM ai_report_policy_rules
			WHERE active = true
			ORDER BY priority DESC, rule_code ASC, revision DESC
			""")
			.query((resultSet, rowNumber) -> new PolicyRow(
				new ReportPolicyRule(
					resultSet.getString("rule_code"),
					resultSet.getString("category"),
					ReportPolicyDecision.valueOf(resultSet.getString("decision")),
					ReportPolicySeverity.valueOf(resultSet.getString("severity")),
					resultSet.getObject("min_confidence", BigDecimal.class),
					ReportEvidenceType.valueOf(resultSet.getString("evidence_types")),
					resultSet.getInt("priority"),
					resultSet.getInt("revision")
				),
				resultSet.getString("content_hash").trim()
			))
			.list();

		return new ReportPolicySnapshot(
			policySetHash(rows),
			rows.stream().map(PolicyRow::rule).toList()
		);
	}

	private String policySetHash(List<PolicyRow> rows) {
		String canonical = rows.stream()
			.map(row -> row.rule().ruleCode() + ':' + row.rule().revision() + ':' + row.contentHash())
			.reduce("", (left, right) -> left.isEmpty() ? right : left + '\n' + right);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm is not available", exception);
		}
	}

	private record PolicyRow(ReportPolicyRule rule, String contentHash) {
	}
}
