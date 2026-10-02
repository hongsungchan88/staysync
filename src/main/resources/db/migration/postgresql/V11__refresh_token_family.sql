-- 리프레시 토큰 재사용 탐지를 "그 로그인의 체인"으로 좁히고, 교체 직후의 짧은 재사용을 유예한다.
-- ADR 0005 결과 절(2026-10-02), 확인-12 5절.
--
-- family_id  — 한 번의 로그인에서 이어진 토큰들의 묶음. 유예 안의 재사용은 같은 묶음 안에서 갈래를 하나 더
--              만들므로 replaced_by 사슬만으로는 묶음을 다 찾을 수 없다. 그래서 칸으로 둔다.
-- rotated_at — 교체된 시각. 유예는 이 시각부터 센다.

ALTER TABLE refresh_token ADD COLUMN family_id  VARCHAR(40);
ALTER TABLE refresh_token ADD COLUMN rotated_at TIMESTAMPTZ;

-- 기존 토큰은 replaced_by 사슬을 따라 뿌리(아무도 가리키지 않는 토큰)마다 한 묶음이다.
-- 뿌리를 먼저 채우지 않으면 살아 있는 토큰과 그 앞의 교체된 토큰이 다른 묶음이 되어,
-- 배포 직후 옛 토큰의 재사용이 살아 있는 쪽을 끊지 못한다.
WITH RECURSIVE chain(id, root) AS (
    SELECT r.id, r.id
      FROM refresh_token r
     WHERE NOT EXISTS (SELECT 1 FROM refresh_token p WHERE p.replaced_by = r.id)
    UNION ALL
    SELECT nxt.id, chain.root
      FROM chain
      JOIN refresh_token cur ON cur.id = chain.id
      JOIN refresh_token nxt ON nxt.id = cur.replaced_by
)
UPDATE refresh_token t
   SET family_id = 'legacy-' || chain.root
  FROM chain
 WHERE chain.id = t.id;

-- 기존 교체분의 rotated_at 은 비워 둔다 — 유예 밖으로 본다(배포 전의 재사용은 그대로 재사용이다).

ALTER TABLE refresh_token ALTER COLUMN family_id SET NOT NULL;

-- 묶음 단위 무효화가 이 인덱스를 쓴다.
CREATE INDEX idx_refresh_family ON refresh_token (family_id);
