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
		"/my/settings", "/my/settings/",
		"/oauth/kakao/callback", "/oauth/kakao/callback/",
		"/questions", "/questions/",
		"/questions/detail", "/questions/detail/"
	})
	public String forwardStaticPage(HttpServletRequest request) {
		String path = HttpRequestPaths.withinApplication(request);
		if (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return "/".equals(path) ? "forward:/index.html" : "forward:" + path + "/index.html";
	}
}
