package shinhan.fibri.ieum.main.auth.service;

public interface KakaoTokenClient {

	String exchangeCode(String code, String redirectUri);
}
