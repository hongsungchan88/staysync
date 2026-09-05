package com.staysync.channel.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * iCal 을 발행하는 척하는 최소 서버.
 *
 * <p>{@code ETag} 와 조건부 요청을 실제로 다룬다. 어댑터가 {@code If-None-Match} 를
 * 싣는지, 304 를 "바뀐 것 없음"으로 옮기는지가 여기서만 확인된다 — 실제 에어비앤비는
 * 미게시 상태라 발행물을 마음대로 바꿔 줄 수 없다(조사-02 2절).
 *
 * <p>어댑터는 진짜를 쓴다. 파싱과 상태 코드 변환이 {@code IcalAdapter} 의 핵심이라
 * 그걸 우회하면 검증할 것이 사라진다.
 */
public final class IcalStubServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> receivedIfNoneMatch = new CopyOnWriteArrayList<>();
    private final AtomicInteger bodyServed = new AtomicInteger();

    private volatile String body = "";
    private volatile String etag;
    private volatile int forcedStatus;

    public IcalStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("iCal 스텁을 열지 못했습니다.", e);
        }
        server.createContext("/calendar.ics", exchange -> {
            receivedIfNoneMatch.add(exchange.getRequestHeaders().getFirst("If-None-Match"));

            int forced = forcedStatus;
            if (forced != 0) {
                exchange.sendResponseHeaders(forced, -1);
                exchange.close();
                return;
            }
            String currentEtag = etag;
            String conditional = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (currentEtag != null && currentEtag.equals(conditional)) {
                // 조건부 요청이 맞아떨어졌다. 본문을 주지 않는다 — 어댑터가 파싱하지
                // 않는다는 것을 "본문을 준 횟수"로 셀 수 있다.
                exchange.getResponseHeaders().add("ETag", currentEtag);
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            if (currentEtag != null) {
                exchange.getResponseHeaders().add("ETag", currentEtag);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            bodyServed.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "text/calendar;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/calendar.ics";
    }

    /** 발행물을 바꾼다. {@code etag} 가 null 이면 조건부 요청을 지원하지 않는 발행자다. */
    public void publish(String body, String etag) {
        this.body = body;
        this.etag = etag;
        this.forcedStatus = 0;
    }

    /** 무슨 요청이 와도 이 상태 코드로 답한다. 0 이면 정상 동작으로 돌아간다. */
    public void respondWith(int status) {
        this.forcedStatus = status;
    }

    /** 본문을 실제로 내보낸 횟수. 304 는 세지 않으므로 "파싱한 횟수"와 같다. */
    public int bodyServed() {
        return bodyServed.get();
    }

    public List<String> receivedIfNoneMatch() {
        // null 이 섞이므로 List.copyOf 를 쓰지 않는다. 조건부 헤더가 없던 요청이다.
        return new java.util.ArrayList<>(receivedIfNoneMatch);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
