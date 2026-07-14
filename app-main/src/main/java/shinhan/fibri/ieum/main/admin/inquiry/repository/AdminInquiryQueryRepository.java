package shinhan.fibri.ieum.main.admin.inquiry.repository;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem;
import shinhan.fibri.ieum.main.inquiry.domain.Inquiry;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;

public interface AdminInquiryQueryRepository extends Repository<Inquiry, Long> {

	default List<AdminInquiryItem> findAdminItems(InquiryStatus status, Long cursorId, int limit) {
		Pageable pageable = PageRequest.of(0, limit);
		if (status == null && cursorId == null) {
			return findAllAdminItems(pageable);
		}
		if (status == null) {
			return findAllAdminItemsBeforeId(cursorId, pageable);
		}
		if (cursorId == null) {
			return findAdminItemsByStatus(status, pageable);
		}
		return findAdminItemsByStatusBeforeId(status, cursorId, pageable);
	}

	@Query("""
		select new shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem(
			i.id, i.userId, u.email, i.title, i.content, i.status,
			i.answer, i.answeredBy, i.answeredAt, i.createdAt)
		from Inquiry i left join User u on u.id = i.userId
		where i.status = :status
		order by i.id desc
		""")
	List<AdminInquiryItem> findAdminItemsByStatus(@Param("status") InquiryStatus status, Pageable pageable);

	@Query("""
		select new shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem(
			i.id, i.userId, u.email, i.title, i.content, i.status,
			i.answer, i.answeredBy, i.answeredAt, i.createdAt)
		from Inquiry i left join User u on u.id = i.userId
		where i.status = :status and i.id < :cursorId
		order by i.id desc
		""")
	List<AdminInquiryItem> findAdminItemsByStatusBeforeId(
		@Param("status") InquiryStatus status,
		@Param("cursorId") Long cursorId,
		Pageable pageable
	);

	@Query("""
		select new shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem(
			i.id, i.userId, u.email, i.title, i.content, i.status,
			i.answer, i.answeredBy, i.answeredAt, i.createdAt)
		from Inquiry i left join User u on u.id = i.userId
		order by i.id desc
		""")
	List<AdminInquiryItem> findAllAdminItems(Pageable pageable);

	@Query("""
		select new shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem(
			i.id, i.userId, u.email, i.title, i.content, i.status,
			i.answer, i.answeredBy, i.answeredAt, i.createdAt)
		from Inquiry i left join User u on u.id = i.userId
		where i.id < :cursorId
		order by i.id desc
		""")
	List<AdminInquiryItem> findAllAdminItemsBeforeId(@Param("cursorId") Long cursorId, Pageable pageable);
}
