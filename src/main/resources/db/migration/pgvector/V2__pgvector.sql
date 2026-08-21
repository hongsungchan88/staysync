-- =====================================================================
-- pgvector 전용 마이그레이션 (docker 프로파일에서만 실행한다)
--
-- local 프로파일이 쓰는 내장 PostgreSQL 바이너리에는 pgvector 가 포함되어
-- 있지 않다. 그래서 기본 스키마(V1)는 임베딩을 REAL[] 로 두고, 확장을 쓸 수
-- 있는 환경에서만 이 마이그레이션이 컬럼 타입을 바꾸고 인덱스를 건다.
--
-- 두 프로파일은 서로 다른 데이터베이스를 쓰므로 마이그레이션 이력이 달라도
-- 문제되지 않는다. local 은 V1 까지, docker 는 V2 까지 적용된다.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE knowledge_chunk
    ALTER COLUMN embedding TYPE vector(1536) USING embedding::vector;

CREATE INDEX IF NOT EXISTS idx_chunk_vector
    ON knowledge_chunk USING hnsw (embedding vector_cosine_ops);
