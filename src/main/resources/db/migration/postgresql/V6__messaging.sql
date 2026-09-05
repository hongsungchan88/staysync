-- P4 14주차. 통합 인박스와 자동 발송.
--
-- V1 에 message_thread 와 message 가 있지만 셋이 모자란다.
-- V1 을 고치지 않고 새 버전을 더한다.

-- ---------------------------------------------------------------------
-- 1. 수신 멱등성
-- ---------------------------------------------------------------------
--
-- 채널 쪽 메시지 식별자. 예약 수신의 (channel_code, channel_booking_id) 와 같은 자리다.
-- 시뮬레이터는 같은 메시지를 두 번 보내고(11주차 결정), 실제 채널의 웹훅도 최소 1회
-- 전달이라 중복이 정상이다. 막는 것은 우리 쪽 일이다.
--
-- 스레드가 이미 (channel_code, external_id) 로 유일하므로 여기서는 스레드 안에서만
-- 유일하면 된다. 채널 코드를 message 에 복사해 두면 스레드와 갈릴 수 있다.
ALTER TABLE message ADD COLUMN external_id VARCHAR(120);

CREATE UNIQUE INDEX uq_message_external ON message (thread_id, external_id)
    WHERE external_id IS NOT NULL;

-- 우리가 보낸 메시지는 채널 식별자가 없다. 대신 발송 작업이 두 번 만들어지지 않게
-- 하는 것은 sync_job 의 uq_syncjob_pending 이 맡는다(V4).

-- ---------------------------------------------------------------------
-- 2. 메시지 템플릿
-- ---------------------------------------------------------------------
--
-- 변수는 {{guestName}} 처럼 이중 중괄호다. 치환할 값이 없으면 발송을 막는다 —
-- {{guestName}} 이 그대로 나간 메시지는 되돌릴 수 없다.
CREATE TABLE message_template (
    id          BIGSERIAL PRIMARY KEY,
    org_id      BIGINT NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    code        VARCHAR(60) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    body        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_template_code UNIQUE (org_id, code)
);

-- ---------------------------------------------------------------------
-- 3. 자동 발송 규칙
-- ---------------------------------------------------------------------
--
-- 계획서 8.5 의 트리거 넷. 예약 확정만 Outbox 이벤트로 오고 나머지 셋은 시간 기반이다.
CREATE TABLE message_rule (
    id          BIGSERIAL PRIMARY KEY,
    org_id      BIGINT NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    property_id BIGINT REFERENCES property(id) ON DELETE CASCADE,
    trigger_type VARCHAR(30) NOT NULL,
    template_id BIGINT NOT NULL REFERENCES message_template(id) ON DELETE CASCADE,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_rule_trigger CHECK (trigger_type IN
        ('RESERVATION_CONFIRMED','BEFORE_CHECK_IN','ON_CHECK_OUT','AFTER_CHECK_OUT'))
);
CREATE INDEX idx_rule_enabled ON message_rule (org_id, trigger_type) WHERE enabled;

-- ---------------------------------------------------------------------
-- 4. 발송 이력 — 같은 규칙이 같은 예약에 두 번 나가지 않게 한다
-- ---------------------------------------------------------------------
--
-- **유일 제약이 이 표의 존재 이유다.** Outbox 는 최소 1회 전달이라 같은 예약 확정
-- 이벤트가 두 번 올 수 있고, 시간 기반 스케줄러는 재기동하면 같은 날짜를 다시 훑는다.
-- 애플리케이션에서 "이미 보냈나" 를 확인하고 넣으면 그 사이에 두 번째가 끼어든다.
-- 데이터베이스가 막게 한다.
--
-- 행을 지우지 않는다. 지우면 그 예약에 같은 규칙이 다시 나간다.
CREATE TABLE message_dispatch (
    id             BIGSERIAL PRIMARY KEY,
    rule_id        BIGINT NOT NULL REFERENCES message_rule(id) ON DELETE CASCADE,
    reservation_id BIGINT NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    message_id     BIGINT REFERENCES message(id) ON DELETE SET NULL,
    dispatched_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_dispatch_once UNIQUE (rule_id, reservation_id)
);
