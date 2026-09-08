package com.staysync.booking;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 만료 배치가 한 주기 상한에 닿았을 때 그 사실을 로그로 남기는지 확인한다.
 *
 * <p>상한 자체는 락을 오래 쥐지 않기 위한 안전장치라 옳다. 문제는 <b>조용히 잘리는
 * 경우</b>다. 유입이 처리 속도를 앞지르면 밀린 HOLD 가 계속 쌓이는데, 배치는 매 주기
 * 100건을 성공적으로 처리하므로 지표상으로는 정상으로 보인다. 팔 수 있는 방이 묶여
 * 있는데 아무도 모르는 상태가 된다.
 *
 * <p>그래서 경고 로그가 이 상황의 유일한 신호다. 로그가 사라지면 이 테스트가 실패한다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class HoldExpiryBatchLimitTest {

    @Autowired
    private HoldExpiryJob holdExpiryJob;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    private ListAppender<ILoggingEvent> appender;
    private Logger jobLogger;

    @BeforeEach
    void 로그를_수집한다() {
        jobLogger = (Logger) LoggerFactory.getLogger(HoldExpiryJob.class);
        appender = new ListAppender<>();
        appender.start();
        jobLogger.addAppender(appender);
    }

    @AfterEach
    void 로그_수집을_끝낸다() {
        jobLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("상한에 닿으면 경고를 남긴다")
    void 상한에_닿으면_경고를_남긴다() {
        // 상한보다 한 건 많이 만든다. 한 주기로는 다 처리하지 못하는 상황이다.
        HoldFixture fixture = holdsExceedingLimit();

        int expired = holdExpiryJob.expireDueHolds(OffsetDateTime.now().plusHours(1));

        assertThat(expired)
                .as("한 주기에는 상한만큼만 처리한다")
                .isEqualTo(HoldExpiryJob.BATCH_LIMIT);
        assertThat(warnMessages())
                .as("남은 건이 있다는 사실이 로그로 드러나야 한다")
                .anySatisfy(message -> assertThat(message).contains("상한"));

        // 남은 한 건은 다음 주기에 처리된다.
        //
        // **전역 반환 건수를 세지 않는다.** 이 배치는 데이터베이스 전체를 훑으므로
        // 다른 테스트가 남긴 HOLD 까지 함께 처리하고, 그러면 이 숫자가 실행 조합에
        // 따라 달라진다. 13주차 고아 되살리기에서 같은 모양으로 겪었고 CLAUDE.md 에
        // 적혀 있다. 이 판매 단위의 행만 본다.
        holdExpiryJob.expireDueHolds(OffsetDateTime.now().plusHours(1));
        assertThat(남은_홀드수(fixture.unitId()))
                .as("두 주기면 이 단위의 홀드가 모두 만료돼야 한다")
                .isZero();
    }

    @Test
    @DisplayName("상한에 닿지 않으면 경고를 남기지 않는다")
    void 상한에_닿지_않으면_경고가_없다() {
        Long unitId = unit("소량", (short) 5);
        for (int i = 0; i < 3; i++) {
            bookingService.hold(propertyId, unitId, period(i), BigDecimal.valueOf(100000), null);
        }

        holdExpiryJob.expireDueHolds(OffsetDateTime.now().plusHours(1));

        assertThat(warnMessages())
                .as("정상 범위에서 경고가 나오면 진짜 경고가 묻힌다")
                .noneSatisfy(message -> assertThat(message).contains("상한"));
    }

    private java.util.List<String> warnMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private record HoldFixture(Long unitId, int total) {
    }

    /**
     * 상한을 넘는 HOLD 를 만든다.
     *
     * <p>판매 단위 하나에 재고를 넉넉히 주고 날짜를 달리해 겹치지 않게 만든다.
     * 같은 날짜에 몰면 재고 제약에 먼저 걸린다.
     */
    private HoldFixture holdsExceedingLimit() {
        int total = HoldExpiryJob.BATCH_LIMIT + 1;
        Long unitId = unit("상한초과", (short) 1);
        for (int i = 0; i < total; i++) {
            bookingService.hold(propertyId, unitId, period(i), BigDecimal.valueOf(100000), null);
        }
        return new HoldFixture(unitId, total);
    }

    /** 그 판매 단위에 아직 살아 있는 HOLD 수. 전역 건수 대신 이것을 본다. */
    private int 남은_홀드수(Long unitId) {
        Integer left = jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE unit_id = ? AND status = 'HOLD'",
                Integer.class, unitId);
        return left == null ? 0 : left;
    }

    private Long propertyId;

    private Long unit(String name, short totalUnits) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES ('상한테스트') RETURNING id", Long.class);
        propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        return unitRegistration.register(
                propertyId, name, UnitKind.ENTIRE_PLACE, totalUnits, BigDecimal.valueOf(100000));
    }

    /** 날짜가 겹치지 않게 하루씩 민다. 다른 테스트와도 겹치지 않는 구간을 쓴다. */
    private static StayPeriod period(int offset) {
        LocalDate base = LocalDate.of(2028, 1, 1).plusDays(offset);
        return new StayPeriod(base, base.plusDays(1));
    }
}
