package com.staysync.shared.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Docker 없이 실제 PostgreSQL 을 띄운다.
 *
 * <p>zonky embedded-postgres 가 플랫폼에 맞는 PostgreSQL 바이너리를 내려받아
 * 별도 프로세스로 구동한다. 가상화 계층이 없으므로 Docker 가 필요 없고,
 * Windows 에서는 Microsoft Visual C++ 2013 재배포 패키지만 있으면 된다.
 *
 * <p>H2 대신 이 방식을 쓰는 이유는 세 가지다. 마이그레이션 파일을 한 벌만
 * 유지하면 되고, {@code FOR UPDATE SKIP LOCKED} 와 생성 컬럼, 부분 인덱스가
 * 그대로 동작하며, 동시성 테스트를 로컬에서 돌릴 수 있다.
 *
 * <p>{@code clean-on-start} 를 켜면 기동할 때마다 데이터 디렉터리를 새로 만든다.
 * 스키마가 자주 바뀌는 초기 개발 단계에서는 이쪽이 편하다. 마이그레이션 내용이
 * 바뀌어도 Flyway 체크섬 오류가 나지 않기 때문이다. 스키마가 안정되면 꺼서
 * 데이터를 보존한다.
 */
@Configuration
@ConditionalOnProperty(name = "staysync.embedded-postgres.enabled", havingValue = "true")
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(
            @Value("${staysync.embedded-postgres.port:15432}") int port,
            @Value("${staysync.embedded-postgres.data-directory:.localdb}") String dataDirectory,
            @Value("${staysync.embedded-postgres.clean-on-start:true}") boolean cleanOnStart)
            throws IOException {

        Path dir = Path.of(dataDirectory).toAbsolutePath();
        Files.createDirectories(dir);
        log.info("내장 PostgreSQL 을 기동한다. port={} dataDir={} cleanOnStart={}",
                port, dir, cleanOnStart);

        File dataDir = dir.toFile();
        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(dataDir)
                .setCleanDataDirectory(cleanOnStart)
                .start();

        log.info("내장 PostgreSQL 준비 완료. jdbcUrl={}", postgres.getJdbcUrl("postgres", "postgres"));
        return postgres;
    }

    @Bean
    @Primary
    public DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }
}
