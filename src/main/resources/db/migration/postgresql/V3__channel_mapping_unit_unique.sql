-- 같은 연결 안에서 한 판매 단위가 두 번 매핑되지 않게 막는다.
--
-- V1 의 uq_channel_mapping 은 (connection_id, external_unit_id, external_rate_id) 라
-- 우리 쪽 판매 단위를 보지 않는다. 채널 쪽 식별자만 다르면 같은 재고가 두 번 등록되고,
-- 그러면 12주차 동기화 워커가 같은 재고를 두 번 보낸다. 증상은 워커가 돌기 시작해야
-- 나오고 그때는 원인이 매핑에 있다는 것을 알아내기 어렵다.
--
-- V1 을 고치지 않고 새 버전을 더한다. clean-on-start 를 끈 뒤로 적용된 마이그레이션은
-- 체크섬이 관리된다.
CREATE UNIQUE INDEX uq_channel_mapping_unit ON channel_mapping (connection_id, unit_id);
