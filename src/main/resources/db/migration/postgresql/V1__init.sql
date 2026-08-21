-- =====================================================================
-- StaySync 초기 스키마 (PostgreSQL 16)
-- 데이터 모델: 안 B (2계층) — Property → Unit → RatePlan
-- 결정 근거: docs/결정문서-01-데이터모델.md
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 조직과 사용자
-- ---------------------------------------------------------------------

CREATE TABLE organization (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_account (
    id            BIGSERIAL PRIMARY KEY,
    org_id        BIGINT NOT NULL REFERENCES organization(id),
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,          -- OWNER | MANAGER | HOUSEKEEPER
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT chk_user_role CHECK (role IN ('OWNER','MANAGER','HOUSEKEEPER'))
);
CREATE INDEX idx_user_org ON user_account (org_id);

-- ---------------------------------------------------------------------
-- 2. 숙소와 판매 단위
--    안 B: RoomType/Room 2계층을 Unit 하나로 통합한다.
--    Unit 이 곧 물리 공간이자 판매 상품이며, total_units 로 수량을 표현한다.
-- ---------------------------------------------------------------------

CREATE TABLE property (
    id             BIGSERIAL PRIMARY KEY,
    org_id         BIGINT NOT NULL REFERENCES organization(id),
    name           VARCHAR(200) NOT NULL,
    timezone       VARCHAR(50)  NOT NULL DEFAULT 'Asia/Seoul',
    currency       VARCHAR(3)   NOT NULL DEFAULT 'KRW',
    check_in_time  TIME NOT NULL DEFAULT '15:00',
    check_out_time TIME NOT NULL DEFAULT '11:00',
    address        VARCHAR(500),
    lat            NUMERIC(10,7),
    lng            NUMERIC(10,7),
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_property_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_property_org ON property (org_id);

CREATE TABLE unit (
    id             BIGSERIAL PRIMARY KEY,
    property_id    BIGINT NOT NULL REFERENCES property(id) ON DELETE CASCADE,
    name           VARCHAR(200) NOT NULL,        -- "성수동 오피스텔", "작은방"
    unit_kind      VARCHAR(20)  NOT NULL,        -- 에어비앤비 숙소 유형과 대응
    occupancy_std  SMALLINT NOT NULL DEFAULT 2,
    occupancy_max  SMALLINT NOT NULL DEFAULT 4,
    total_units    SMALLINT NOT NULL DEFAULT 1,  -- 독채는 1, 도미토리는 침대 수
    base_price     NUMERIC(12,2) NOT NULL DEFAULT 0,
    floor_price    NUMERIC(12,2),                -- 요금 추천 하한
    ceiling_price  NUMERIC(12,2),                -- 요금 추천 상한
    housekeeping   VARCHAR(20) NOT NULL DEFAULT 'CLEAN',
    sort_order     SMALLINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_unit_kind CHECK (unit_kind IN ('ENTIRE_PLACE','PRIVATE_ROOM','SHARED_ROOM')),
    CONSTRAINT chk_unit_housekeeping CHECK (housekeeping IN ('CLEAN','DIRTY','INSPECTING','BLOCKED')),
    CONSTRAINT chk_unit_total CHECK (total_units >= 1),
    CONSTRAINT chk_unit_occupancy CHECK (occupancy_max >= occupancy_std),
    CONSTRAINT chk_unit_price_range CHECK (
        floor_price IS NULL OR ceiling_price IS NULL OR ceiling_price >= floor_price)
);
CREATE INDEX idx_unit_property ON unit (property_id);

-- 요금제는 유지한다. Booking.com / Channex 의 ARI 전송 최소 단위가 rate_plan 이기 때문.
-- 숙소 생성 시 "기본" 요금제를 자동 생성해 호스트가 개념을 의식하지 않게 한다.
CREATE TABLE rate_plan (
    id            BIGSERIAL PRIMARY KEY,
    unit_id       BIGINT NOT NULL REFERENCES unit(id) ON DELETE CASCADE,
    name          VARCHAR(200) NOT NULL,
    refundable    BOOLEAN NOT NULL DEFAULT true,
    cancel_policy JSONB,                        -- {"free_until_days":3,"penalty_rate":0.5}
    is_default    BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_rateplan_unit ON rate_plan (unit_id);
CREATE UNIQUE INDEX uq_rateplan_default ON rate_plan (unit_id) WHERE is_default;

-- ---------------------------------------------------------------------
-- 3. 재고 원장 — 중복예약 방지의 기준 테이블
-- ---------------------------------------------------------------------

CREATE TABLE inventory_ledger (
    unit_id      BIGINT   NOT NULL REFERENCES unit(id) ON DELETE CASCADE,
    stay_date    DATE     NOT NULL,
    total_units  SMALLINT NOT NULL,
    booked_units SMALLINT NOT NULL DEFAULT 0,
    held_units   SMALLINT NOT NULL DEFAULT 0,
    stop_sell    BOOLEAN  NOT NULL DEFAULT false,
    version      BIGINT   NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (unit_id, stay_date),
    -- 최종 방어선: 로직 결함이 통과해도 데이터베이스가 초과 판매를 거부한다.
    CONSTRAINT chk_no_oversell CHECK (booked_units + held_units <= total_units),
    CONSTRAINT chk_non_negative CHECK (booked_units >= 0 AND held_units >= 0)
);
CREATE INDEX idx_inventory_date ON inventory_ledger (stay_date);

CREATE TABLE rate_calendar (
    rate_plan_id        BIGINT NOT NULL REFERENCES rate_plan(id) ON DELETE CASCADE,
    stay_date           DATE   NOT NULL,
    price               NUMERIC(12,2) NOT NULL,
    min_stay            SMALLINT NOT NULL DEFAULT 1,
    max_stay            SMALLINT,
    closed_to_arrival   BOOLEAN NOT NULL DEFAULT false,
    closed_to_departure BOOLEAN NOT NULL DEFAULT false,
    stop_sell           BOOLEAN NOT NULL DEFAULT false,
    source              VARCHAR(20) NOT NULL DEFAULT 'MANUAL',  -- MANUAL | RULE | AI
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (rate_plan_id, stay_date),
    CONSTRAINT chk_rate_source CHECK (source IN ('MANUAL','RULE','AI')),
    CONSTRAINT chk_rate_positive CHECK (price >= 0),
    CONSTRAINT chk_rate_stay CHECK (max_stay IS NULL OR max_stay >= min_stay)
);
CREATE INDEX idx_ratecal_date ON rate_calendar (stay_date);

-- ---------------------------------------------------------------------
-- 4. 게스트와 예약
-- ---------------------------------------------------------------------

CREATE TABLE guest (
    id            BIGSERIAL PRIMARY KEY,
    org_id        BIGINT NOT NULL REFERENCES organization(id),
    name          VARCHAR(120) NOT NULL,
    phone_enc     TEXT,           -- AES-256 암호화 저장
    phone_hash    VARCHAR(64),    -- 검색용 해시
    email_enc     TEXT,
    email_hash    VARCHAR(64),
    locale        VARCHAR(10) NOT NULL DEFAULT 'ko',
    memo          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_guest_phone_hash ON guest (phone_hash);
CREATE INDEX idx_guest_email_hash ON guest (email_hash);

CREATE TABLE reservation (
    id                 BIGSERIAL PRIMARY KEY,
    property_id        BIGINT NOT NULL REFERENCES property(id),
    unit_id            BIGINT NOT NULL REFERENCES unit(id),
    rate_plan_id       BIGINT REFERENCES rate_plan(id),
    guest_id           BIGINT REFERENCES guest(id),
    channel_code       VARCHAR(40) NOT NULL,      -- DIRECT | AIRBNB_ICAL | MOCK_A ...
    channel_booking_id VARCHAR(120),
    confirmation_code  VARCHAR(20) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    check_in           DATE NOT NULL,
    check_out          DATE NOT NULL,
    adults             SMALLINT NOT NULL DEFAULT 2,
    children           SMALLINT NOT NULL DEFAULT 0,
    total_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    channel_commission NUMERIC(12,2) NOT NULL DEFAULT 0,
    net_amount         NUMERIC(12,2) GENERATED ALWAYS AS
                       (total_amount - channel_commission) STORED,
    raw_payload        JSONB,                     -- 채널 원본 (재처리·디버깅용)
    revision           INT NOT NULL DEFAULT 1,    -- OTA 예약 수정 버전
    hold_expires_at    TIMESTAMPTZ,               -- HOLD 상태 만료 시각
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_confirmation UNIQUE (confirmation_code),
    -- 같은 예약이 여러 번 전달되어도 중복 생성되지 않게 하는 멱등성 키
    CONSTRAINT uq_channel_booking UNIQUE (channel_code, channel_booking_id),
    CONSTRAINT chk_res_dates CHECK (check_out > check_in),
    CONSTRAINT chk_res_status CHECK (status IN
        ('HOLD','CONFIRMED','CANCELLED','CHECKED_IN','CHECKED_OUT','NO_SHOW','EXPIRED'))
);
CREATE INDEX idx_res_stay   ON reservation (property_id, check_in, check_out);
CREATE INDEX idx_res_unit   ON reservation (unit_id, check_in);
CREATE INDEX idx_res_active ON reservation (status) WHERE status IN ('HOLD','CONFIRMED');
CREATE INDEX idx_res_hold   ON reservation (hold_expires_at) WHERE status = 'HOLD';

-- 박 단위 스냅샷. 캘린더 렌더링과 리포트 집계 성능을 위해 비정규화한다.
CREATE TABLE reservation_night (
    reservation_id BIGINT NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    stay_date      DATE   NOT NULL,
    unit_id        BIGINT NOT NULL REFERENCES unit(id),
    price          NUMERIC(12,2) NOT NULL DEFAULT 0,
    PRIMARY KEY (reservation_id, stay_date)
);
CREATE INDEX idx_resnight_unit_date ON reservation_night (unit_id, stay_date);

CREATE TABLE payment (
    id             BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    provider       VARCHAR(30) NOT NULL,        -- PORTONE | CHANNEL | CASH
    provider_tx_id VARCHAR(120),
    kind           VARCHAR(20) NOT NULL,        -- CHARGE | REFUND
    amount         NUMERIC(12,2) NOT NULL,
    status         VARCHAR(20) NOT NULL,        -- PENDING | PAID | FAILED | REFUNDED
    paid_at        TIMESTAMPTZ,
    raw_payload    JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_tx UNIQUE (provider, provider_tx_id),
    CONSTRAINT chk_payment_kind CHECK (kind IN ('CHARGE','REFUND'))
);
CREATE INDEX idx_payment_res ON payment (reservation_id);

-- ---------------------------------------------------------------------
-- 5. 채널 연동
-- ---------------------------------------------------------------------

CREATE TABLE channel_connection (
    id               BIGSERIAL PRIMARY KEY,
    property_id      BIGINT NOT NULL REFERENCES property(id) ON DELETE CASCADE,
    channel_code     VARCHAR(40) NOT NULL,
    adapter_type     VARCHAR(20) NOT NULL,       -- ICAL | CHANNEX | MOCK
    display_name     VARCHAR(100),
    credentials      JSONB,                      -- 암호화 저장 (API 키, iCal URL)
    commission_rate  NUMERIC(5,4) NOT NULL DEFAULT 0,
    inventory_buffer SMALLINT NOT NULL DEFAULT 0, -- 지연형 채널에 유보할 재고 수
    sync_enabled     BOOLEAN NOT NULL DEFAULT true,
    etag             VARCHAR(200),               -- iCal 조건부 요청용
    last_event_count INT,                        -- iCal 대량 소실 방어용
    last_sync_at     TIMESTAMPTZ,
    last_sync_status VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_channel_conn UNIQUE (property_id, channel_code),
    CONSTRAINT chk_adapter_type CHECK (adapter_type IN ('ICAL','CHANNEX','MOCK')),
    CONSTRAINT chk_commission CHECK (commission_rate >= 0 AND commission_rate < 1)
);

CREATE TABLE channel_mapping (
    id               BIGSERIAL PRIMARY KEY,
    connection_id    BIGINT NOT NULL REFERENCES channel_connection(id) ON DELETE CASCADE,
    unit_id          BIGINT NOT NULL REFERENCES unit(id) ON DELETE CASCADE,
    rate_plan_id     BIGINT REFERENCES rate_plan(id),
    external_unit_id VARCHAR(120) NOT NULL,      -- 에어비앤비 리스팅 ID 등
    external_rate_id VARCHAR(120),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_channel_mapping UNIQUE (connection_id, external_unit_id, external_rate_id)
);
CREATE INDEX idx_mapping_unit ON channel_mapping (unit_id);

CREATE TABLE sync_job (
    id              BIGSERIAL PRIMARY KEY,
    connection_id   BIGINT NOT NULL REFERENCES channel_connection(id) ON DELETE CASCADE,
    job_type        VARCHAR(30) NOT NULL,        -- PUSH_ARI | PULL_BOOKING | PULL_ICAL
    payload         JSONB NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt         SMALLINT NOT NULL DEFAULT 0,
    next_run_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_syncjob_status CHECK (status IN
        ('PENDING','RUNNING','SUCCESS','FAILED','DEAD'))
);
CREATE UNIQUE INDEX uq_syncjob_key ON sync_job (connection_id, idempotency_key);
-- 워커가 FOR UPDATE SKIP LOCKED 로 집어가는 경로
CREATE INDEX idx_syncjob_claim ON sync_job (next_run_at) WHERE status = 'PENDING';

CREATE TABLE outbox_event (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   BIGINT NOT NULL,
    event_type     VARCHAR(80) NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    retry_count    SMALLINT NOT NULL DEFAULT 0,
    last_error     TEXT
);
CREATE INDEX idx_outbox_pending ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE overbooking_conflict (
    id              BIGSERIAL PRIMARY KEY,
    property_id     BIGINT NOT NULL REFERENCES property(id) ON DELETE CASCADE,
    unit_id         BIGINT NOT NULL REFERENCES unit(id) ON DELETE CASCADE,
    stay_date       DATE NOT NULL,
    reservation_ids BIGINT[] NOT NULL,
    severity        VARCHAR(10) NOT NULL,        -- WARN | CRITICAL
    status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    resolution      VARCHAR(40),                 -- UPGRADED | RELOCATED | CANCELLED | ABSORBED
    resolved_by     BIGINT REFERENCES user_account(id),
    resolved_at     TIMESTAMPTZ,
    memo            TEXT,
    detected_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_conflict_status CHECK (status IN ('OPEN','RESOLVED','IGNORED')),
    CONSTRAINT chk_conflict_severity CHECK (severity IN ('WARN','CRITICAL'))
);
CREATE INDEX idx_conflict_open ON overbooking_conflict (property_id, stay_date)
    WHERE status = 'OPEN';

-- ---------------------------------------------------------------------
-- 6. 운영과 메시지
-- ---------------------------------------------------------------------

CREATE TABLE ops_task (
    id             BIGSERIAL PRIMARY KEY,
    property_id    BIGINT NOT NULL REFERENCES property(id) ON DELETE CASCADE,
    unit_id        BIGINT REFERENCES unit(id) ON DELETE CASCADE,
    reservation_id BIGINT REFERENCES reservation(id) ON DELETE SET NULL,
    task_type      VARCHAR(20) NOT NULL,        -- CLEANING | MAINTENANCE | INSPECTION
    status         VARCHAR(20) NOT NULL DEFAULT 'TODO',
    assignee_id    BIGINT REFERENCES user_account(id),
    due_from       TIMESTAMPTZ,
    due_to         TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    photo_urls     TEXT[],
    memo           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_task_type CHECK (task_type IN ('CLEANING','MAINTENANCE','INSPECTION')),
    CONSTRAINT chk_task_status CHECK (status IN ('TODO','IN_PROGRESS','DONE','BLOCKED'))
);
CREATE INDEX idx_task_board ON ops_task (property_id, status, due_from);

CREATE TABLE message_thread (
    id             BIGSERIAL PRIMARY KEY,
    property_id    BIGINT NOT NULL REFERENCES property(id) ON DELETE CASCADE,
    reservation_id BIGINT REFERENCES reservation(id) ON DELETE SET NULL,
    guest_id       BIGINT REFERENCES guest(id),
    channel_code   VARCHAR(40) NOT NULL,
    external_id    VARCHAR(120),
    subject        VARCHAR(200),
    unread_count   SMALLINT NOT NULL DEFAULT 0,
    last_message_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_thread_external UNIQUE (channel_code, external_id)
);
CREATE INDEX idx_thread_recent ON message_thread (property_id, last_message_at DESC);

CREATE TABLE message (
    id           BIGSERIAL PRIMARY KEY,
    thread_id    BIGINT NOT NULL REFERENCES message_thread(id) ON DELETE CASCADE,
    direction    VARCHAR(10) NOT NULL,          -- INBOUND | OUTBOUND
    sender       VARCHAR(20) NOT NULL,          -- GUEST | HOST | AI | SYSTEM
    body         TEXT NOT NULL,
    ai_generated BOOLEAN NOT NULL DEFAULT false,
    ai_confidence NUMERIC(4,3),
    ai_citations INT[],
    sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_msg_direction CHECK (direction IN ('INBOUND','OUTBOUND')),
    CONSTRAINT chk_msg_sender CHECK (sender IN ('GUEST','HOST','AI','SYSTEM'))
);
CREATE INDEX idx_message_thread ON message (thread_id, sent_at);

-- ---------------------------------------------------------------------
-- 7. AI 지식베이스
-- ---------------------------------------------------------------------

CREATE TABLE knowledge_doc (
    id          BIGSERIAL PRIMARY KEY,
    property_id BIGINT NOT NULL REFERENCES property(id) ON DELETE CASCADE,
    doc_type    VARCHAR(40) NOT NULL,   -- HOUSE_RULE | FAQ | AMENITY | DIRECTION | POLICY
    title       VARCHAR(200) NOT NULL,
    body        TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kdoc_property ON knowledge_doc (property_id);

CREATE TABLE knowledge_chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT NOT NULL REFERENCES knowledge_doc(id) ON DELETE CASCADE,
    property_id BIGINT NOT NULL,
    content     TEXT NOT NULL,
    -- 임베딩은 이식성을 위해 실수 배열로 둔다. 내장 PostgreSQL 바이너리에는
    -- pgvector 가 없기 때문이다. docker 프로파일에서는 V2 가 이 컬럼을
    -- pgvector 의 vector 타입으로 바꾸고 HNSW 인덱스를 건다.
    embedding   REAL[],
    token_count INT
);
CREATE INDEX idx_chunk_property ON knowledge_chunk (property_id);
-- 하이브리드 검색의 키워드 축
CREATE INDEX idx_chunk_fts ON knowledge_chunk
    USING gin (to_tsvector('simple', content));

-- ---------------------------------------------------------------------
-- 8. 감사와 스케줄 락
-- ---------------------------------------------------------------------

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    actor_id     BIGINT REFERENCES user_account(id),
    actor_kind   VARCHAR(20) NOT NULL DEFAULT 'USER',  -- USER | SYSTEM | CHANNEL | AI
    entity_type  VARCHAR(50) NOT NULL,
    entity_id    BIGINT NOT NULL,
    action       VARCHAR(40) NOT NULL,
    before_value JSONB,
    after_value  JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id, occurred_at DESC);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
