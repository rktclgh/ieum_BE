package shinhan.fibri.ieum.main.notification.push;

public interface WebPushDispatcher {

	void dispatch(long userId, WebPushDispatchRequest request);
}
