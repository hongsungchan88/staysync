package com.staysync.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.staysync.channel.domain.SyncJob;
import com.staysync.channel.domain.SyncJobType;
import com.staysync.channel.port.AriUpdateCommand;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 채널로 보낼 변경을 모았다가 한 번에 내보낸다. 계획서 6.5 의 {@code AriCoalescingBuffer} 다.
 *
 * <p><b>왜 모으는가.</b> Channex 제한이 숙소당 분당 20회이고 요금·제약 10회와 재고 10회가
 * 따로 매겨진다(조사-01). 캘린더에서 30일을 드래그해 요금을 바꾸면 셀 30개가 바뀌는데,
 * 셀마다 보내면 한 번의 조작이 한도를 넘긴다. 6초 윈도로 모으고 연속된 같은 값을 날짜
 * 구간으로 압축하면 그 조작이 호출 한 번으로 끝난다.
 *
 * <p><b>Mock 은 분당 제한이 없지만 버퍼를 우회하지 않는다.</b> 채널마다 다른 경로를
 * 만들면 검증한 것과 실제로 도는 것이 갈라진다.
 *
 * <p><b>나중 값이 이긴다.</b> 6초 안에 25만원 → 23만원으로 바꾸면 23만원 하나만 나간다.
 * 둘 다 보내면 채널이 중간값을 잠깐 갖게 되고, 그 사이에 들어온 예약은 틀린 요금으로
 * 성사된다.
 *
 * <p>버퍼는 <b>메모리에 있다.</b> 앱이 죽으면 아직 flush 되지 않은 6초치가 사라진다.
 * 그 자리를 메우는 것이 13주차의 정기 재동기화(계획서 6.6)이고, 6초를 잃는 대신
 * 한도를 지키는 것이 이 설계의 교환이다.
 */
@Component
public class AriCoalescingBuffer {

    private static final Logger log = LoggerFactory.getLogger(AriCoalescingBuffer.class);

    private final ObjectMapper json;
    private final SyncJobRepository jobs;
    private final long windowMs;

    /** 연결·판매 단위마다 모으는 자리. 창이 열린 시각과 날짜별 값을 함께 들고 있다. */
    private final Map<Key, Window> windows = new ConcurrentHashMap<>();

    AriCoalescingBuffer(ObjectMapper json, SyncJobRepository jobs,
                        @Value("${staysync.channel.ari-window-ms:6000}") long windowMs) {
        this.json = json;
        this.jobs = jobs;
        this.windowMs = windowMs;
    }

    /** 연결 하나와 판매 단위 하나. 채널 쪽 식별자는 flush 할 때 명령에 실린다. */
    private record Key(Long connectionId, String externalUnitId, String externalRateId) {
    }

    /**
     * 하루치 변경. {@code null} 인 항목은 "이번에 바꾸지 않는다"는 뜻이다.
     *
     * <p>요금 변경과 재고 변경이 같은 날짜에 겹칠 수 있어 값마다 따로 둔다. 하나로
     * 뭉치면 재고만 바뀐 날의 요금이 {@code null} 로 나가 채널이 요금을 지운다.
     */
    private record Cell(Integer availability, BigDecimal rate, Integer minStay, Boolean stopSell) {

        /** 나중 값이 이긴다. 다만 {@code null} 은 "안 바꿈"이라 앞의 값을 지우지 않는다. */
        Cell mergedWith(Cell later) {
            return new Cell(
                    later.availability != null ? later.availability : availability,
                    later.rate != null ? later.rate : rate,
                    later.minStay != null ? later.minStay : minStay,
                    later.stopSell != null ? later.stopSell : stopSell);
        }

        boolean sameValues(Cell other) {
            return Objects.equals(availability, other.availability)
                    && (rate == null ? other.rate == null
                            : other.rate != null && rate.compareTo(other.rate) == 0)
                    && Objects.equals(minStay, other.minStay)
                    && Objects.equals(stopSell, other.stopSell);
        }
    }

    private static final class Window {
        private final long openedAtMs = System.currentTimeMillis();
        /** 날짜 오름차순으로 들고 있어야 구간 압축이 한 번의 훑기로 끝난다. */
        private final TreeMap<LocalDate, Cell> cells = new TreeMap<>();
    }

    /**
     * 하루치 변경을 버퍼에 넣는다.
     *
     * @param externalRateId 요금 계층이 따로 있는 채널만 값이 있다. iCal 은 {@code null}
     */
    public synchronized void enqueue(Long connectionId, String externalUnitId, String externalRateId,
                                     LocalDate date, Integer availability, BigDecimal rate,
                                     Integer minStay, Boolean stopSell) {
        Key key = new Key(connectionId, externalUnitId, externalRateId);
        Window window = windows.computeIfAbsent(key, k -> new Window());
        Cell incoming = new Cell(availability, rate, minStay, stopSell);
        window.cells.merge(date, incoming, Cell::mergedWith);
    }

    /**
     * 주기 실행. 창이 열린 지 윈도를 넘긴 것만 내보낸다.
     *
     * <p>{@code OutboxRelay} 와 같은 이유로 주기를 설정으로 뺐다 — 테스트가 스케줄러와
     * 같은 버퍼를 두고 경쟁하면 "나중 값 하나만 나간다"가 타이밍에 따라 갈린다.
     */
    @Scheduled(fixedDelayString = "${staysync.channel.buffer-tick-ms:1000}",
            initialDelayString = "${staysync.channel.buffer-tick-ms:1000}")
    public void tick() {
        flushDue(System.currentTimeMillis());
    }

    /** 테스트가 시계를 밀지 않고 부를 수 있게 분리했다. */
    public int flushDue(long nowMs) {
        List<Key> due = windows.entrySet().stream()
                .filter(entry -> nowMs - entry.getValue().openedAtMs >= windowMs)
                .map(Map.Entry::getKey)
                .toList();

        int flushed = 0;
        for (Key key : due) {
            if (flushOne(key)) {
                flushed++;
            }
        }
        return flushed;
    }

    /** 남아 있는 것을 전부 내보낸다. 테스트가 6초를 기다리지 않게 하는 통로다. */
    public int flushAll() {
        return flushDue(Long.MAX_VALUE);
    }

    /**
     * 한 창을 작업으로 만든다.
     *
     * <p>버퍼에서 꺼내는 것과 작업을 만드는 것을 한 트랜잭션에 두지 않는다. 트랜잭션이
     * 롤백돼도 버퍼는 이미 비어 있어 변경을 잃기 때문이다. 대신 <b>실패하면 되돌려
     * 넣는다.</b>
     */
    private boolean flushOne(Key key) {
        Window window;
        synchronized (this) {
            window = windows.remove(key);
        }
        if (window == null || window.cells.isEmpty()) {
            return false;
        }
        try {
            return enqueueJob(key, compress(window.cells));
        } catch (DataIntegrityViolationException e) {
            // uq_syncjob_pending 이 막았다. 같은 변경이 이미 대기 중이라는 뜻이므로
            // 되돌려 넣지 않는다. 되돌리면 영원히 같은 자리에서 부딪힌다.
            log.debug("같은 ARI 작업이 이미 대기 중이라 넘긴다. connectionId={}", key.connectionId());
            return false;
        } catch (RuntimeException e) {
            // 다음 주기에 다시 시도한다. 되돌려 넣지 않으면 이 6초치가 조용히 사라지고,
            // 채널은 옛 값을 든 채로 남는다.
            synchronized (this) {
                Window restored = windows.computeIfAbsent(key, k -> new Window());
                window.cells.forEach((date, cell) -> restored.cells.merge(date, cell, Cell::mergedWith));
            }
            log.warn("ARI 작업을 만들지 못해 버퍼에 되돌려 넣었다. connectionId={} 사유={}",
                    key.connectionId(), e.toString());
            return false;
        }
    }

    /**
     * 연속된 날짜에 같은 값이면 한 구간으로 묶는다.
     *
     * <p>6개월치 변경이 호출 한 번으로 끝나게 하는 것이 목표다(Channex 자가 인증 항목 8).
     * 압축하지 않으면 180일이 세그먼트 180개가 된다.
     */
    private static List<AriUpdateCommand.Segment> compress(TreeMap<LocalDate, Cell> cells) {
        List<AriUpdateCommand.Segment> segments = new ArrayList<>();

        LocalDate runFrom = null;
        LocalDate runTo = null;
        Cell runCell = null;

        for (Map.Entry<LocalDate, Cell> entry : cells.entrySet()) {
            LocalDate date = entry.getKey();
            Cell cell = entry.getValue();

            boolean continues = runCell != null
                    && date.equals(runTo.plusDays(1))
                    && runCell.sameValues(cell);
            if (continues) {
                runTo = date;
                continue;
            }
            if (runCell != null) {
                segments.add(toSegment(runFrom, runTo, runCell));
            }
            runFrom = date;
            runTo = date;
            runCell = cell;
        }
        if (runCell != null) {
            segments.add(toSegment(runFrom, runTo, runCell));
        }
        return segments;
    }

    private static AriUpdateCommand.Segment toSegment(LocalDate from, LocalDate to, Cell cell) {
        return new AriUpdateCommand.Segment(from, to, cell.availability(), cell.rate(),
                cell.minStay(), null, null, null, cell.stopSell());
    }

    /**
     * 작업 행 하나를 만든다.
     *
     * <p>{@code @Transactional} 을 붙이지 않았다. 같은 클래스 안에서 부르는 메서드라
     * 프록시를 거치지 않아 어차피 걸리지 않는다(이 프로젝트에서 반복해 적어 둔 함정이다).
     * 실제로 필요하지도 않다 — 확인과 저장 사이의 경합은 {@code uq_syncjob_pending} 이
     * 막고, 그건 부르는 쪽이 "이미 대기 중"으로 받는다.
     */
    private boolean enqueueJob(Key key, List<AriUpdateCommand.Segment> segments) {
        AriUpdateCommand command = new AriUpdateCommand(
                key.externalUnitId(), key.externalRateId(), segments);
        String payload = serialize(command);
        String idempotencyKey = idempotencyKey(key, segments);

        if (jobs.existsOutstanding(key.connectionId(), idempotencyKey)) {
            // 같은 변경이 이미 대기 중이다. Outbox 의 최소 1회 전달이 만든 중복이며
            // 오류가 아니다.
            log.debug("같은 ARI 작업이 이미 대기 중이라 넘긴다. connectionId={}", key.connectionId());
            return false;
        }
        jobs.save(new SyncJob(key.connectionId(), SyncJobType.PUSH_ARI, payload, idempotencyKey));
        return true;
    }

    /**
     * {@code hash(connId, dateRange, values)}. 계획서 6.5 그대로다.
     *
     * <p>값까지 넣는 이유는, 같은 날짜의 <b>다른 값</b>은 서로 다른 작업이어야 하기
     * 때문이다. 날짜만 넣으면 25만원과 23만원이 같은 키가 되어 뒤엣것이 막힌다.
     */
    private String idempotencyKey(Key key, List<AriUpdateCommand.Segment> segments) {
        StringBuilder raw = new StringBuilder()
                .append(key.connectionId()).append('|')
                .append(key.externalUnitId()).append('|')
                .append(key.externalRateId());
        for (AriUpdateCommand.Segment segment : segments) {
            raw.append('|').append(segment.from()).append('~').append(segment.to())
                    .append(':').append(segment.availability())
                    .append(':').append(segment.rate())
                    .append(':').append(segment.minStay())
                    .append(':').append(segment.stopSell());
        }
        return sha256(raw.toString());
    }

    /** 컬럼이 {@code VARCHAR(120)} 이라 원문을 그대로 넣을 수 없다. 64자로 줄인다. */
    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
        }
    }

    private String serialize(AriUpdateCommand command) {
        try {
            return json.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ARI 명령을 직렬화하지 못했습니다.", e);
        }
    }
}
