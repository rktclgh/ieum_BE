package shinhan.fibri.ieum.main.report.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.main.report.domain.Report;

public interface ReportRepository extends JpaRepository<Report, Long> {
}
