-- =====================================================================
-- V9. 금액을 모르는 예약을 0 원과 구분한다 (작업지시-16)
--
-- iCal 발행물에는 금액이 없다. 그런데 V1 의 total_amount 가 NOT NULL DEFAULT 0 이라
-- IcalAdapter 가 0 을 넣었고, 리포트는 그 0 원짜리 박 251 개를 판매 객실박으로 세어
-- ADR 8원·RevPAR 2원을 내놓았다(검토-01 2절 7번). "0 원"과 "모른다"가 같은 값으로
-- 저장되는 것이 본체다.
--
-- 미상은 NULL 이다. 별도 플래그 컬럼을 두지 않는 이유 —
--   * 두 컬럼이면 서로 어긋날 수 있다(금액 5,000 에 플래그 "미상"). NULL 은 한 자리다
--   * sum() 이 NULL 을 건너뛰므로 "확인된 금액의 합"이 질의 그대로다
--   * net_amount 생성 컬럼(total_amount - channel_commission)도 따라서 NULL 이 된다 —
--     모르는 금액에서 수수료를 뺀 값도 모르는 것이 맞다
--
-- 기존 행의 판정은 "0 원이면 미상"이 아니라 어느 경로에서 왔는지다. ICAL 어댑터
-- 연결의 채널 코드로 들어온 예약은 금액을 받은 적이 없다. 수기·위젯·Mock 예약은
-- 그대로 둔다 — 그쪽의 0 원은 무료 숙박이거나 조정이고, 그것은 0 이 맞다.
--
-- 배포된 DB(staysync.kr)의 운영 데이터(에어비앤비 iCal 예약 54건)를 고치는 첫
-- 마이그레이션이다. 적용 전 /opt/staysync/backup.sh 를 돌린다.
-- =====================================================================

ALTER TABLE reservation
    ALTER COLUMN total_amount DROP NOT NULL,
    ALTER COLUMN total_amount DROP DEFAULT;

ALTER TABLE reservation_night
    ALTER COLUMN price DROP NOT NULL,
    ALTER COLUMN price DROP DEFAULT;

-- 어느 채널이 금액을 주지 않는지는 어댑터 종류가 안다. 같은 숙소·같은 채널 코드의
-- 연결이 ICAL 이면 그 예약은 iCal 로 들어온 것이다(V8 뒤로 iCal 연결은 숙소당
-- 여럿일 수 있지만 채널 코드는 같다).
UPDATE reservation r
   SET total_amount = NULL
 WHERE EXISTS (SELECT 1
                 FROM channel_connection c
                WHERE c.property_id = r.property_id
                  AND c.channel_code = r.channel_code
                  AND c.adapter_type = 'ICAL');

-- 박 행은 예약의 금액을 박 수로 나눈 것이다. 예약이 미상이면 박도 미상이다.
UPDATE reservation_night n
   SET price = NULL
 WHERE EXISTS (SELECT 1
                 FROM reservation r
                WHERE r.id = n.reservation_id
                  AND r.total_amount IS NULL);
