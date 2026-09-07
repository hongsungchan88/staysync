package com.staysync.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.staysync.booking.BookingService;
import com.staysync.booking.domain.Reservation;
import com.staysync.booking.domain.StayPeriod;
import com.staysync.ops.domain.OpsTask;
import com.staysync.ops.domain.TaskStatus;
import com.staysync.property.UnitRegistrationService;
import com.staysync.property.domain.UnitKind;
import com.staysync.shared.outbox.OutboxRelay;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 완료 조건 1~7, 12. 체크아웃으로 만들어지는 청소 태스크와 청소 상태.
 *
 * <p><b>릴레이를 거쳐 태스크가 만들어지는지를 본다.</b> {@code OpsTaskService} 를 직접
 * 부르면 소비자가 실제로 이벤트를 받는지가 검증되지 않는다 — 9주차 SSE, 12주차 채널
 * 전파, 14주차 자동 발송에 이어 네 번째 입주자이고, 등록이 빠지면 아무 일도 일어나지
 * 않으면서 로그도 조용하다.
 *
 * <p>릴레이는 {@code build.gradle} 이 주기를 멈춰 뒀다. 테스트가
 * {@link OutboxRelay#relayPending()} 을 직접 부른다.
 */
@SpringBootTest(properties = {
        "staysync.embedded-postgres.port=15433",
        "staysync.embedded-postgres.data-directory=.localdb-test"
})
@ActiveProfiles("local")
class CleaningTaskTest {

    private static final LocalDate 체크인 = LocalDate.of(2027, 4, 1);
    private static final LocalDate 체크아웃 = 체크인.plusDays(2);

    @Autowired
    private BookingService booking;

    @Autowired
    private OpsTaskService tasks;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private UnitRegistrationService unitRegistration;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    // --- 완료 조건 1 ---------------------------------------------------------

    @Test
    @DisplayName("체크아웃 이벤트로 청소 태스크가 생긴다")
    void 체크아웃하면_청소_태스크가_생긴다() {
        Fixture f = given("청소생성");
        Long reservationId = 체크아웃까지(f);

        relay.relayPending();

        List<OpsTask> board = tasks.board(f.orgId(), null, null, null);
        assertThat(board).hasSize(1);
        OpsTask task = board.get(0);
        assertThat(task.getTaskType().name()).isEqualTo("CLEANING");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(task.getUnitId()).isEqualTo(f.unitId());
        assertThat(task.getReservationId()).isEqualTo(reservationId);
    }

    // --- 완료 조건 2 ---------------------------------------------------------

    @Test
    @DisplayName("기한의 끝이 다음 체크인 시각이다")
    void 기한의_끝은_다음_체크인이다() {
        Fixture f = given("청소기한");
        // 체크아웃 다음 날 새 예약이 들어온다. 청소는 그 전에 끝나야 한다.
        booking.registerManual(f.propertyId(), f.unitId(),
                new StayPeriod(체크아웃.plusDays(1), 체크아웃.plusDays(3)),
                BigDecimal.valueOf(200_000), (short) 2, (short) 0, null);
        체크아웃까지(f);

        relay.relayPending();

        OpsTask task = tasks.board(f.orgId(), null, null, null).get(0);
        // 숙소 기본값은 체크인 15:00, 체크아웃 11:00 이다. 돌려받은 값은 UTC 기준이라
        // 같은 순간을 이 지역 시각으로 옮겨 비교한다 — 문자 그대로 비교하면 시차만큼
        // 어긋난 값을 보게 된다.
        assertThat(현지시각(task.getDueFrom()))
                .isEqualTo(체크아웃.atTime(11, 0));
        assertThat(현지시각(task.getDueTo()))
                .isEqualTo(체크아웃.plusDays(1).atTime(15, 0));
    }

    @Test
    @DisplayName("다음 예약이 없으면 기한이 열려 있다")
    void 다음_예약이_없으면_기한이_열린다() {
        Fixture f = given("청소열림");
        체크아웃까지(f);

        relay.relayPending();

        OpsTask task = tasks.board(f.orgId(), null, null, null).get(0);
        assertThat(task.getDueTo())
                .as("없는 날짜를 지어내면 아직 오지 않은 마감이 지난 것으로 표시된다")
                .isNull();
        assertThat(task.isOverdue(java.time.OffsetDateTime.now().plusYears(5)))
                .as("기한이 열려 있으면 지날 수 없다")
                .isFalse();
    }

    // --- 완료 조건 3 ---------------------------------------------------------

    @Test
    @DisplayName("같은 이벤트가 두 번 전달돼도 태스크는 하나다")
    void 중복_전달에도_태스크는_하나다() {
        Fixture f = given("청소중복");
        Long reservationId = 체크아웃까지(f);

        relay.relayPending();
        // Outbox 는 최소 1회 전달이다. 같은 이벤트를 한 번 더 흘린다.
        jdbc.update("""
                INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, payload)
                SELECT aggregate_type, aggregate_id, event_type, payload FROM outbox_event
                WHERE aggregate_id = ? AND event_type = 'RESERVATION_CHECKED_OUT'
                """, reservationId);
        relay.relayPending();

        assertThat(tasks.board(f.orgId(), null, null, null))
                .as("두 번 만들면 같은 방을 두 번 청소하러 간다")
                .hasSize(1);
    }

    // --- 완료 조건 4 ---------------------------------------------------------

    @Test
    @DisplayName("취소된 예약에는 태스크가 생기지 않는다")
    void 취소된_예약에는_생기지_않는다() {
        Fixture f = given("청소취소");
        Long reservationId = 체크아웃까지(f);

        // 이벤트가 만들어진 뒤에 취소된다. 페이로드의 상태는 CHECKED_OUT 그대로다.
        jdbc.update("UPDATE reservation SET status = 'CANCELLED' WHERE id = ?", reservationId);

        relay.relayPending();

        assertThat(tasks.board(f.orgId(), null, null, null))
                .as("이벤트의 상태를 믿으면 취소된 예약에 청소가 잡힌다")
                .isEmpty();
    }

    // --- 완료 조건 5·6 -------------------------------------------------------

    @Test
    @DisplayName("체크아웃하면 판매 단위가 DIRTY 가 되고 완료하면 CLEAN 으로 돌아온다")
    void 청소_상태가_오간다() {
        Fixture f = given("청소상태");
        체크아웃까지(f);
        assertThat(housekeepingOf(f.unitId())).isEqualTo("CLEAN");

        relay.relayPending();
        assertThat(housekeepingOf(f.unitId())).isEqualTo("DIRTY");

        OpsTask task = tasks.board(f.orgId(), null, null, null).get(0);
        tasks.move(task.getId(), f.orgId(), TaskStatus.DONE);

        assertThat(housekeepingOf(f.unitId())).isEqualTo("CLEAN");
        assertThat(completedAtOf(task.getId())).isNotNull();
    }

    // --- 완료 조건 7 ---------------------------------------------------------

    @Test
    @DisplayName("태스크 완료가 롤백되면 판매 단위 상태도 되돌아온다")
    void 완료가_롤백되면_청소_상태도_되돌아온다() {
        Fixture f = given("청소롤백");
        체크아웃까지(f);
        relay.relayPending();
        OpsTask task = tasks.board(f.orgId(), null, null, null).get(0);

        // 완료 처리 뒤에 바깥이 실패한다. move 는 전파가 REQUIRED 라 이 트랜잭션에
        // 합류하므로, 둘이 한 경계면 태스크도 판매 단위도 함께 되돌아와야 한다.
        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(status -> {
            tasks.move(task.getId(), f.orgId(), TaskStatus.DONE);
            throw new IllegalStateException("완료 처리 뒤의 실패를 흉내 낸다");
        })).isInstanceOf(IllegalStateException.class);

        // 하나만 남으면 "청소 끝났는데 더러움" 또는 그 반대다. 둘 다 화면에서는
        // 정상으로 보이고, 어긋난 것은 담당자가 헛걸음한 뒤에야 드러난다.
        assertThat(statusOf(task.getId())).isEqualTo("TODO");
        assertThat(housekeepingOf(f.unitId())).isEqualTo("DIRTY");
    }

    // --- 완료 조건 12 --------------------------------------------------------

    @Test
    @DisplayName("태스크가 생기면 인박스에 시스템 메시지가 남는다")
    void 인박스에_알림이_남는다() {
        Fixture f = given("청소알림");
        Long reservationId = 체크아웃까지(f);

        relay.relayPending();

        // messaging 의 저장소는 그 모듈 안에서만 보인다. SQL 로 확인한다.
        Map<String, Object> note = jdbc.queryForMap("""
                SELECT m.sender, m.direction, m.body, t.unread_count
                FROM message m JOIN message_thread t ON t.id = m.thread_id
                WHERE t.reservation_id = ?
                """, reservationId);

        assertThat(note.get("sender")).isEqualTo("SYSTEM");
        assertThat(note.get("direction"))
                .as("OUTBOUND 면 게스트에게 나간 것과 구분되지 않고 발송 경로에 실린다")
                .isEqualTo("INBOUND");
        assertThat((String) note.get("body")).contains("청소");
        assertThat(((Number) note.get("unread_count")).intValue())
                .as("목록에 표시가 나지 않으면 담당자가 보지 않는다")
                .isEqualTo(1);
    }

    // --- 담당자와 필터 -------------------------------------------------------

    @Test
    @DisplayName("담당자로 거르면 그 사람의 태스크만 나온다")
    void 담당자로_거른다() {
        Fixture f = given("청소담당");
        체크아웃까지(f);
        relay.relayPending();

        OpsTask task = tasks.board(f.orgId(), null, null, null).get(0);
        tasks.assign(task.getId(), f.orgId(), "김청소");

        assertThat(tasks.board(f.orgId(), null, null, "김청소")).hasSize(1);
        assertThat(tasks.board(f.orgId(), null, null, "박정비")).isEmpty();
    }

    @Test
    @DisplayName("남의 조직 태스크는 404 다")
    void 남의_태스크는_보이지_않는다() {
        Fixture 내것 = given("청소내것");
        Fixture 남의것 = given("청소남의것");
        체크아웃까지(남의것);
        relay.relayPending();

        assertThat(tasks.board(내것.orgId(), null, null, null))
                .as("소유가 아닌 자원은 없음으로 답한다")
                .isEmpty();
    }

    // --- 픽스처 ---------------------------------------------------------------

    /** 예약을 만들어 체크인·체크아웃까지 보낸다. 체크아웃이 Outbox 이벤트를 남긴다. */
    private Long 체크아웃까지(Fixture f) {
        Reservation reservation = booking.registerManual(
                f.propertyId(), f.unitId(), new StayPeriod(체크인, 체크아웃),
                BigDecimal.valueOf(200_000), (short) 2, (short) 0, null);
        booking.checkIn(reservation.getId());
        booking.checkOut(reservation.getId());
        return reservation.getId();
    }

    private static java.time.LocalDateTime 현지시각(java.time.OffsetDateTime at) {
        return at.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    private String statusOf(Long taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM ops_task WHERE id = ?", String.class, taskId);
    }

    private String housekeepingOf(Long unitId) {
        return jdbc.queryForObject(
                "SELECT housekeeping FROM unit WHERE id = ?", String.class, unitId);
    }

    private Object completedAtOf(Long taskId) {
        return jdbc.queryForObject(
                "SELECT completed_at FROM ops_task WHERE id = ?", Object.class, taskId);
    }

    private Fixture given(String name) {
        Long orgId = jdbc.queryForObject(
                "INSERT INTO organization (name) VALUES (?) RETURNING id",
                Long.class, name + " 조직");
        Long propertyId = jdbc.queryForObject(
                "INSERT INTO property (org_id, name) VALUES (?, ?) RETURNING id",
                Long.class, orgId, name + " 숙소");
        Long unitId = unitRegistration.register(
                propertyId, name + " 객실", UnitKind.ENTIRE_PLACE, (short) 1,
                BigDecimal.valueOf(100_000));
        return new Fixture(orgId, propertyId, unitId);
    }

    private record Fixture(Long orgId, Long propertyId, Long unitId) {
    }
}
