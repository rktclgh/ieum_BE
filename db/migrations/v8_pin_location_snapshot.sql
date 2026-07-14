-- v8: 질문·모임 공통 위치 스냅샷을 pins에 추가
-- 전제: 운영 데이터가 없는 DB 초기화. meetings.place_name 구형 규격은 만들지 않는다.
-- 위치 공간 계산의 정본은 location, 화면 표시 정본은 address/detail_address/label이다.

BEGIN;

ALTER TABLE pins
    ADD COLUMN address VARCHAR(255) NOT NULL,
    ADD COLUMN detail_address VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN label VARCHAR(100) NOT NULL DEFAULT '',
    ADD CONSTRAINT chk_pins_address_not_blank CHECK (btrim(address) <> '');

COMMIT;
