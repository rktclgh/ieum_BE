package shinhan.fibri.ieum.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import shinhan.fibri.ieum.main.support.HttpRequestPaths;

@Controller
public class StaticPageController {

	@GetMapping({
		"/",
		"/chats", "/chats/",
		"/chats/notices", "/chats/notices/",
		"/chats/report", "/chats/report/",
		"/chats/room", "/chats/room/",
		"/chats/schedule", "/chats/schedule/",
		"/friends", "/friends/",
		"/join", "/join/",
		"/join/social", "/join/social/",
		"/login", "/login/",
		"/meetups/detail", "/meetups/detail/",
		"/my", "/my/",
		"/my/edit", "/my/edit/",
		"/my/inquiry", "/my/inquiry/",
		"/my/notifications", "/my/notifications/",
		"/my/permissions", "/my/permissions/",
		"/oauth/kakao/callback", "/oauth/kakao/callback/",
		"/questions", "/questions/",
		"/questions/detail", "/questions/detail/",
		"/admin", "/admin/",
		"/admin/login", "/admin/login/",
		"/admin/users", "/admin/users/",
		"/admin/users/detail", "/admin/users/detail/",
		"/admin/knowledge", "/admin/knowledge/",
		"/admin/content", "/admin/content/",
		"/admin/reports", "/admin/reports/",
		"/admin/reports/detail", "/admin/reports/detail/",
		"/admin/inquiries", "/admin/inquiries/"
	})
	public String forwardStaticPage(HttpServletRequest request) {
		String path = HttpRequestPaths.withinApplication(request);
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return "/".equals(path) ? "forward:/index.html" : "forward:" + path + "/index.html";
	}
}
