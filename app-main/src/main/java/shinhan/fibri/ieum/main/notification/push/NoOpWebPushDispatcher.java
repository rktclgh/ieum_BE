package shinhan.fibri.ieum.main.notification.push;

public final class NoOpWebPushDispatcher implements WebPushDispatcher {

	@Override
	public void dispatch(long userId, WebPushDispatchRequest request) {
	}
}
