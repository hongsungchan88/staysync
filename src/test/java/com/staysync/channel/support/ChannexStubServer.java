package com.staysync.channel.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Channex 노릇을 하는 최소 서버. <b>응답은 스테이징에서 실제로 받은 본문을 그대로 쓴다</b>
 * (작업지시-17 4절 — 손으로 적은 응답은 검증이 아니라 가정이다). 본문은 {@link Responses}.
 *
 * <p>{@code ChannelStubServer} 를 안 쓰는 이유는 둘이다. 그것은 상태 코드만 짜 넣고
 * 본문이 늘 {@code []} 인데, Channex 의 실패는 <b>200 과 경고 본문</b>으로 온다. 헤더도
 * 다르다({@code user-api-key}).
 */
public final class ChannexStubServer implements AutoCloseable {

    /** 한 요청. 경로·본문·키를 남긴다. */
    public record Received(String method, String pathAndQuery, String body, String apiKey) {
    }

    /** 한 응답. */
    public record Reply(int status, String body, Map<String, String> headers) {

        public static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Reply> plan = r -> Reply.ok(Responses.TASK_ACCEPTED);

    public ChannexStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Channex 스텁을 열지 못했습니다.", e);
        }
        server.createContext("/api/", exchange -> {
            Received r = new Received(exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("user-api-key"));
            received.add(r);
            Reply reply = plan.apply(r);
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public void reply(Function<Received, Reply> plan) {
        this.plan = plan;
    }

    public void replyAll(Reply reply) {
        this.plan = r -> reply;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> received(String pathPrefix) {
        return received.stream().filter(r -> r.pathAndQuery().startsWith(pathPrefix)).toList();
    }

    public void reset() {
        received.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * 스테이징에서 2026-09-22 에 실제로 받은 본문(확인-10 2절). 키·개인정보 없음.
     * 식별자는 시험 숙소(USD)의 것이다.
     */
    public static final class Responses {

        private Responses() {
        }

        /** 값이 받아들여졌다. {@code data} 에 task 하나. */
        public static final String TASK_ACCEPTED =
                "{\"data\":[{\"id\":\"e590b554-04e7-4083-84a4-13df07698c8f\",\"type\":\"task\"}],\"meta\":{\"message\":\"Success\"}}";

        /** 0 요금 — 200 인데 반영되지 않았다. */
        public static final String WARNING_RATE_ZERO =
                "{\"data\":[],\"meta\":{\"message\":\"Success\",\"warnings\":[{\"warning\":{\"rate\":[\"must be greater than 0\"]},"
                        + "\"date\":\"2026-10-22\",\"property_id\":\"17e754e7-9aa8-456a-ad0a-94e1d54bc8f3\","
                        + "\"rate_plan_id\":\"46b69549-6f8b-4a55-b594-979850f45379\",\"rate\":\"0\"}]}}";

        /** 지난 날짜. */
        public static final String WARNING_PAST_DATE =
                "{\"data\":[],\"meta\":{\"message\":\"Success\",\"warnings\":[{\"warning\":{\"date\":[\"Past date is not allowed\"]},"
                        + "\"date\":\"2026-09-21\",\"property_id\":\"17e754e7-9aa8-456a-ad0a-94e1d54bc8f3\","
                        + "\"rate_plan_id\":\"46b69549-6f8b-4a55-b594-979850f45379\",\"rate\":\"120.00\"}]}}";

        /** 없는 객실 — 경고가 객체가 아니라 문자열이다. */
        public static final String WARNING_ROOM_NOT_FOUND =
                "{\"data\":[],\"meta\":{\"message\":\"Success\",\"warnings\":[{\"warning\":\"Not found room_type for this change\","
                        + "\"date\":\"2026-10-22\",\"property_id\":\"17e754e7-9aa8-456a-ad0a-94e1d54bc8f3\","
                        + "\"room_type_id\":\"00000000-0000-0000-0000-000000000000\",\"availability\":1}]}}";

        /** {@code GET /restrictions} — 요금제 키 아래 날짜마다 재고·요금·제약이 같이 온다. */
        public static final String RESTRICTIONS_SNAPSHOT =
                "{\"data\":{\"46b69549-6f8b-4a55-b594-979850f45379\":{"
                        + "\"2026-10-22\":{\"availability\":1,\"closed_to_arrival\":false,\"closed_to_departure\":false,\"max_stay\":0,"
                        + "\"min_stay_arrival\":2,\"min_stay_through\":1,\"rate\":\"120.00\",\"stop_sell\":false,\"unavailable_reasons\":[]},"
                        + "\"2026-10-23\":{\"availability\":0,\"closed_to_arrival\":false,\"closed_to_departure\":false,\"max_stay\":0,"
                        + "\"min_stay_arrival\":2,\"min_stay_through\":1,\"rate\":\"120.00\",\"stop_sell\":true,\"unavailable_reasons\":[]}}}}";

        /** {@code GET /availability} — 객실 유형 키 아래 날짜: 수량. */
        public static final String AVAILABILITY_SNAPSHOT =
                "{\"data\":{\"92f88770-4d38-4ff8-839d-542672d92c3e\":{\"2026-10-22\":1,\"2026-10-23\":1}}}";

        /**
         * 스테이징 실물 — <b>Booking CRS 로 만든 예약의 첫 리비전</b>(2026-09-22 10:19 UTC, 확인-10 3절).
         * 부킹닷컴 공용 테스트 숙소가 전부 남의 손에 있어 Channex 인증 시험 11 이 허용하는 CRS 경로로
         * 만들었다(작업지시-17 8.3). 투숙객은 가명("Te st"), 메일은 예시 주소, 계정 식별자
         * {@code system_id} 는 0 으로 지웠다. {@code raw_message} 안의 금액이 최소 단위 정수(18054)인
         * 것이 보인다 — 속성의 {@code "180.54"} 와 같은 값이다.
         */
        public static final String REVISION_CRS_NEW = "{\"attributes\":{\"id\":\"22861a65-c065-450c-ac2c-7b7894dc1c5d\",\"meta\":{},\"status\":\"new\",\"services\":[],\"currency\":\"USD\",\"amount\":\"361.08\",\"agent\":null,\"unique_id\":\"BDC-STAYSYNC-CRS-001\",\"inserted_at\":\"2026-09-22T10:19:36.251613\",\"channel_id\":null,\"ota_reservation_code\":\"STAYSYNC-CRS-001\",\"system_id\":\"00000000-0000-0000-0000-000000000000\",\"ota_name\":\"Booking.com\",\"property_id\":\"17e754e7-9aa8-456a-ad0a-94e1d54bc8f3\",\"booking_id\":\"87358c26-ac21-4962-aca4-571807e76238\",\"arrival_date\":\"2026-11-10\",\"arrival_hour\":null,\"customer\":{\"meta\":null,\"name\":\"Te\",\"state\":null,\"zip\":null,\"address\":null,\"city\":null,\"country\":\"KR\",\"language\":null,\"mail\":\"test@example.com\",\"phone\":null,\"surname\":\"st\"},\"departure_date\":\"2026-11-12\",\"deposits\":[],\"notes\":null,\"ota_commission\":\"0.00\",\"payment_collect\":\"ota\",\"payment_type\":\"credit_card\",\"rooms\":[{\"meta\":null,\"taxes\":[],\"services\":[],\"amount\":\"361.08\",\"days\":{\"2026-11-10\":\"180.54\",\"2026-11-11\":\"180.54\"},\"ota_commission\":null,\"guests\":[{\"name\":\"Te\",\"surname\":\"st\"}],\"occupancy\":{\"children\":0,\"adults\":2,\"ages\":[],\"infants\":0},\"rate_plan_id\":\"46b69549-6f8b-4a55-b594-979850f45379\",\"room_type_id\":\"92f88770-4d38-4ff8-839d-542672d92c3e\",\"booking_room_id\":\"47253c43-556b-409e-a767-a83ce04e92a6\",\"checkout_date\":\"2026-11-12\",\"checkin_date\":\"2026-11-10\",\"is_cancelled\":false,\"ota_unique_id\":null}],\"occupancy\":{\"children\":0,\"adults\":2,\"ages\":[],\"infants\":0},\"guarantee\":null,\"secondary_ota\":null,\"acknowledge_status\":\"pending\",\"raw_message\":\"{\\\"meta\\\":{},\\\"status\\\":\\\"new\\\",\\\"services\\\":[],\\\"currency\\\":\\\"USD\\\",\\\"amount\\\":0,\\\"ota_reservation_code\\\":\\\"STAYSYNC-CRS-001\\\",\\\"ota_name\\\":\\\"Booking.com\\\",\\\"property_id\\\":\\\"17e754e7-9aa8-456a-ad0a-94e1d54bc8f3\\\",\\\"arrival_date\\\":\\\"2026-11-10\\\",\\\"arrival_hour\\\":null,\\\"customer\\\":{\\\"meta\\\":null,\\\"name\\\":\\\"Te\\\",\\\"state\\\":null,\\\"zip\\\":null,\\\"address\\\":null,\\\"city\\\":null,\\\"country\\\":\\\"KR\\\",\\\"language\\\":null,\\\"mail\\\":\\\"test@example.com\\\",\\\"phone\\\":null,\\\"surname\\\":\\\"st\\\"},\\\"departure_date\\\":\\\"2026-11-12\\\",\\\"deposits\\\":[],\\\"notes\\\":null,\\\"ota_commission\\\":0,\\\"payment_collect\\\":\\\"ota\\\",\\\"payment_type\\\":\\\"credit_card\\\",\\\"rooms\\\":[{\\\"meta\\\":null,\\\"taxes\\\":[],\\\"services\\\":[],\\\"amount\\\":0,\\\"days\\\":{\\\"2026-11-10\\\":18054,\\\"2026-11-11\\\":18054},\\\"guests\\\":[{\\\"name\\\":\\\"Te\\\",\\\"surname\\\":\\\"st\\\"}],\\\"occupancy\\\":{\\\"children\\\":0,\\\"adults\\\":2,\\\"ages\\\":[],\\\"infants\\\":0},\\\"rate_plan_id\\\":\\\"46b69549-6f8b-4a55-b594-979850f45379\\\",\\\"room_type_id\\\":\\\"92f88770-4d38-4ff8-839d-542672d92c3e\\\"}]}\",\"is_crs_revision\":true},\"id\":\"22861a65-c065-450c-ac2c-7b7894dc1c5d\",\"type\":\"booking_revision\",\"relationships\":{\"data\":{\"property\":{\"id\":\"17e754e7-9aa8-456a-ad0a-94e1d54bc8f3\",\"type\":\"property\"},\"booking\":{\"id\":\"87358c26-ac21-4962-aca4-571807e76238\",\"type\":\"booking\"}}}}";

        /**
         * 예약 리비전 하나. <b>Channex 문서의 예시를 시험 숙소 식별자로 바꾼 것</b> — 부킹닷컴 경로의
         * 모양(채널 코드·색 매핑을 덮는 단위 테스트용). 실물은 {@link #REVISION_CRS_NEW} 다.
         */
        public static final String REVISION_NEW = revision(
                "03dd7198-c5b7-493c-a889-74d0c2211de7", "cfa33f3b-bd32-4b90-8ef9-bde2bfe986cd",
                "new", "2026-09-22T05:00:00.000000", "2026-11-05", "2026-11-07", "260.00", "USD",
                "92f88770-4d38-4ff8-839d-542672d92c3e", "46b69549-6f8b-4a55-b594-979850f45379");

        /** 같은 모양으로 값만 바꾼 리비전. 피드 {@code data[]} 의 원소 하나다. */
        public static String revision(String id, String bookingId, String status, String insertedAt,
                                      String checkIn, String checkOut, String amount, String currency,
                                      String roomTypeId, String ratePlanId) {
            String amountJson = amount == null ? "null" : "\"" + amount + "\"";
            return "{\"type\":\"booking_revision\",\"id\":\"" + id + "\",\"attributes\":{"
                    + "\"id\":\"" + id + "\",\"property_id\":\"17e754e7-9aa8-456a-ad0a-94e1d54bc8f3\","
                    + "\"booking_id\":\"" + bookingId + "\",\"unique_id\":\"BDC-9996013801\",\"revision_id\":\"" + id + "\","
                    + "\"ota_reservation_code\":\"9996013801\",\"ota_name\":\"Booking.com\",\"status\":\"" + status + "\","
                    + "\"customer\":{\"name\":\"User\",\"surname\":\"Channex\",\"mail\":\"user@channex.io\"},"
                    + "\"rooms\":[{\"room_type_id\":\"" + roomTypeId + "\",\"rate_plan_id\":\"" + ratePlanId + "\","
                    + "\"checkin_date\":\"" + checkIn + "\",\"checkout_date\":\"" + checkOut + "\","
                    + "\"occupancy\":{\"adults\":2,\"children\":0,\"infants\":0},\"amount\":" + amountJson + ",\"days\":{}}],"
                    + "\"arrival_date\":\"" + checkIn + "\",\"departure_date\":\"" + checkOut + "\","
                    + "\"amount\":" + amountJson + ",\"currency\":\"" + currency + "\",\"inserted_at\":\"" + insertedAt + "\"}}";
        }

        /** 틀린 키. */
        public static final String UNAUTHORIZED = "{\"errors\":{\"code\":\"unauthorized\",\"title\":\"Unauthorized\"}}";

        /**
         * 스테이징의 레이트 리밋 헤더 실측값. 정책이 분당 6,000 이라 실제 429 는 못 받았다 —
         * 429 본문은 Channex 문서대로 {@code errors} 꼴로 가정하고, 이 헤더로 대기 시간을 읽는다.
         */
        public static final String RATELIMIT_HEADER = "\"availability\";r=0;t=21, \"availability\";r=359996;t=3561";
    }
}
