-- =====================================================================
-- V10. 초과 예약분을 총수량과 분리한다 (작업지시-18 9절 E)
--
-- V1 의 chk_no_oversell(booked + held <= total) 을 만족시키려고 forceBook 이 그날의
-- total_units 를 올렸고, 해소·취소로 반납돼도 그대로였다. 그래서 초과 예약이 한 번
-- 있었던 날짜는 실제 수량보다 하나 더 팔릴 수 있었고, 그 값이 ARI 로 채널에 나갔다.
--
-- 이제 total_units 는 언제나 판매 단위의 실제 수량이고, 초과분은 overbooked_units 다.
-- overbooked_units 는 정의상 GREATEST(0, booked + held - total) 이며 DB 가 그 등식을
-- 강제한다. 그래서
--   * 반납되면 초과분이 정확히 사라진다 — 등식이 그렇다
--   * 초과 예약 경로(forceBook)만 이 값을 올린다. 다른 경로가 수량을 넘겨 쓰면
--     overbooked 가 0 인 채 등식이 깨져 거부된다. 최종 방어선은 그대로다
-- =====================================================================

-- 순서가 중요하다. 옛 chk_no_oversell(booked + held <= total) 을 먼저 내려야 아래 UPDATE 가
-- 부푼 행(booked + held 가 판매 단위 수량보다 큰 행)의 total 을 내릴 수 있다. UPDATE 를
-- 먼저 하면 그 행에서 옛 제약에 걸려 마이그레이션이 통째로 실패한다 — 운영은 부푼 행이
-- 0 이라 안 드러나고 테스트는 빈 DB 라 못 잡는 종류다(V10MigrationTest 가 부푼 행으로 본다).
ALTER TABLE inventory_ledger DROP CONSTRAINT chk_no_oversell;

ALTER TABLE inventory_ledger
    ADD COLUMN overbooked_units SMALLINT NOT NULL DEFAULT 0;

-- 부푼 행(forceBook)과 판매 단위 수량 변경이 흘러가지 않은 행 둘 다 — 총수량을
-- 판매 단위 수량으로 맞추고 초과분을 계산해 넣는다. 배포 DB 는 충돌이 없어 0 행일 것이다.
UPDATE inventory_ledger l
   SET total_units      = u.total_units,
       overbooked_units = GREATEST(0, l.booked_units + l.held_units - u.total_units)
  FROM unit u
 WHERE u.id = l.unit_id
   AND l.total_units <> u.total_units;

ALTER TABLE inventory_ledger
    ADD CONSTRAINT chk_no_oversell
        CHECK (booked_units + held_units <= total_units + overbooked_units),
    ADD CONSTRAINT chk_overbooked_exact
        CHECK (overbooked_units = GREATEST(0, booked_units + held_units - total_units));
