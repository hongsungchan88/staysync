-- =====================================================================
-- V8. iCal 연결은 숙소당 여럿일 수 있다 (P5 17주차, 확인-09 7절)
--
-- V1 의 UNIQUE (property_id, channel_code) 는 "채널은 숙소 단위로 붙는다"는 가정이다.
-- Channex·Mock 은 그렇다 — 숙소 하나에 계정 하나, 그 안에서 객실을 매핑한다.
-- iCal 은 그 가정이 성립하지 않는다. 내보내기 주소 하나가 리스팅 하나이고 리스팅은
-- 판매 단위 하나다(조사-02 1절). 업체 메종드서촌은 숙소 하나에 2층·3층 피드가 둘이라
-- AIRBNB_ICAL 연결이 둘 필요하다. 채널 코드를 갈라 우회하면 3층 막대가 회색이 되고
-- 리포트 채널 믹스에 에어비앤비가 두 줄로 뜬다(확인-08 이 잡은 결함의 재현).
--
-- 그래서 ICAL 어댑터만 예외로 두고 나머지는 그대로 막는다. 이 제약을 푸는 것으로
-- 열리는 구멍은 없다 — 같은 예약의 이중 유입은 uq_channel_booking
-- (channel_code, channel_booking_id) 가 막고, 연결 하나에 같은 판매 단위 두 번은
-- V3 의 uq_channel_mapping_unit 이 막는다.
-- =====================================================================

ALTER TABLE channel_connection DROP CONSTRAINT uq_channel_conn;

CREATE UNIQUE INDEX uq_channel_conn_per_property
    ON channel_connection (property_id, channel_code)
    WHERE adapter_type <> 'ICAL';
