package shinhan.fibri.ieum.ai.report.service;

import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;

public interface PolicySnapshotProvider {

	ReportPolicySnapshot loadActiveSnapshot();
}
