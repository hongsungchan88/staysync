-- iCal 발행 URL 의 토큰.
--
-- `GET /public/ical/{token}.ics` 가 이 값으로 매핑을 찾는다(계획서 6.2). 매핑마다
-- 하나인 이유는 에어비앤비의 "다른 웹사이트에 연결하기"가 리스팅 하나에 .ics 주소
-- 하나를 받기 때문이다(조사-02 1절). 연결 단위로 두면 판매 단위가 둘일 때 어느
-- 달력을 내보내는지가 모호해진다.
--
-- **해시가 아니라 원문을 담는다.** refresh_token 은 SHA-256 해시만 담지만 저기는
-- 우리가 대조만 하면 되는 값이다. 여기는 호스트가 에어비앤비 화면에 붙여 넣을 URL 을
-- 다시 꺼내 볼 수 있어야 한다. 대신 이 값은 목록·상세 응답에 실리지 않고, 전용
-- 조회 경로에서만 나간다. 로그에도 남기지 않는다.
--
-- V1 을 고치지 않고 새 버전을 더한다.
ALTER TABLE channel_mapping ADD COLUMN export_token VARCHAR(64);

-- 토큰이 겹치면 남의 달력이 나간다. 난수 32바이트라 실제로 겹칠 일은 없지만,
-- 겹쳤을 때의 증상이 "조용히 남의 예약이 새어 나감"이라 데이터베이스가 막게 한다.
CREATE UNIQUE INDEX uq_mapping_export_token ON channel_mapping (export_token)
    WHERE export_token IS NOT NULL;
