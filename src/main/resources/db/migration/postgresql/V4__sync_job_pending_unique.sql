-- 멱등성 키의 유일성을 "아직 끝나지 않은 작업" 안으로 좁힌다.
--
-- V1 의 uq_syncjob_key 는 (connection_id, idempotency_key) 전체에 걸려 있었다.
-- 키가 hash(connId, dateRange, values) 라서(계획서 6.5) 같은 값을 다시 보내는 것이
-- 영영 막힌다. 25만원 → 23만원 → 25만원으로 되돌리면 마지막 전송이 유니크 위반으로
-- 막히고, 채널에는 23만원이 남는다. **우리 쪽 로그는 전부 성공이라 아무 증상이 없다.**
--
-- 막고 싶은 것은 그게 아니라 "같은 변경이 두 번 대기하는 것"이다. Outbox 가 최소 1회
-- 전달이라 같은 이벤트가 두 번 올 수 있고, 버퍼 윈도를 넘겨 오면 작업이 둘 생긴다.
-- 그래서 PENDING·RUNNING 인 행에만 유일성을 건다. 끝난 작업은 다음 전송을 막지 않는다.
--
-- V1 을 고치지 않고 새 버전을 더한다.
DROP INDEX uq_syncjob_key;

CREATE UNIQUE INDEX uq_syncjob_pending ON sync_job (connection_id, idempotency_key)
    WHERE status IN ('PENDING', 'RUNNING');

-- 클레임 쿼리가 연결별 맨 앞 작업을 찾을 때 쓰는 경로.
-- V1 의 idx_syncjob_claim 은 next_run_at 만 봐서 NOT EXISTS 쪽이 순차 스캔이 된다.
CREATE INDEX idx_syncjob_connection_open ON sync_job (connection_id, id)
    WHERE status IN ('PENDING', 'RUNNING');
