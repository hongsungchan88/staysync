package com.staysync.channel.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/**
 * 채널 노릇을 하는 최소 서버. <b>응답을 시나리오대로 짜 넣을 수 있다.</b>
 *
 * <p>11주차의 Mock OTA 시뮬레이터를 대신하지 않는다. 시뮬레이터는 별도 프로세스로 띄워
 * 완료 조건 1·2 를 판정하는 데 쓰고, 여기는 <b>워커의 재시도 정책</b>을 검증한다.
 * "세 번째 요청만 429" 같은 것을 시뮬레이터로는 만들 수 없다 — 시뮬레이터의 악조건은
 * 확률과 시드로 주어지고, 특정 순번을 겨냥하지 못한다.
 *
 * <p>어댑터는 진짜를 쓴다. 상태 코드를 우리 세 갈래로 옮기는 것이
 * {@code MockOtaAdapter} 의 핵심이므로, 그 변환을 우회하면 검증할 것이 사라진다.
 */
public final class ChannelStubServer implements AutoCloseable {

    private final HttpServer server;
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final List<String> receivedApiKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger callCount = new AtomicInteger();

    /** 몇 번째 호출에 어떤 상태 코드를 돌려줄지. 기본은 언제나 202. */
    private volatile IntUnaryOperator statusPlan = call -> 202;

    public ChannelStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("채널 스텁을 열지 못했습니다.", e);
        }
        server.createContext("/api/", exchange -> {
            int call = callCount.incrementAndGet();
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedApiKeys.add(exchange.getRequestHeaders().getFirst("X-Api-Key"));

            int status = statusPlan.applyAsInt(call);
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            if (status == 204 || status >= 400) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** {@code call} 은 1부터 센다. */
    public void respondWith(IntUnaryOperator statusPlan) {
        this.statusPlan = statusPlan;
    }

    /** 처음 {@code n} 번은 실패하고 그 뒤로는 성공한다. */
    public void failFirst(int n, int status) {
        respondWith(call -> call <= n ? status : 202);
    }

    public void alwaysRespond(int status) {
        respondWith(call -> status);
    }

    public int callCount() {
        return callCount.get();
    }

    public List<String> bodies() {
        return List.copyOf(receivedBodies);
    }

    public List<String> apiKeys() {
        return List.copyOf(receivedApiKeys);
    }

    public void reset() {
        receivedBodies.clear();
        receivedApiKeys.clear();
        callCount.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
