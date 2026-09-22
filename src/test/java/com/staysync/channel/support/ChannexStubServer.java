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

        /** 틀린 키. */
        public static final String UNAUTHORIZED = "{\"errors\":{\"code\":\"unauthorized\",\"title\":\"Unauthorized\"}}";

        /**
         * 스테이징의 레이트 리밋 헤더 실측값. 정책이 분당 6,000 이라 실제 429 는 못 받았다 —
         * 429 본문은 Channex 문서대로 {@code errors} 꼴로 가정하고, 이 헤더로 대기 시간을 읽는다.
         */
        public static final String RATELIMIT_HEADER = "\"availability\";r=0;t=21, \"availability\";r=359996;t=3561";
    }
}
