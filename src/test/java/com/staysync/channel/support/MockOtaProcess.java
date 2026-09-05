package com.staysync.channel.support;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 11주차 Mock OTA 시뮬레이터를 <b>별도 프로세스로</b> 띄운다.
 *
 * <p>P3 완료 조건 1·2 는 "실제로 돌려서 판정한다"가 요구다. 같은 JVM 에서 스프링
 * 컨텍스트로 띄우면 두 가지가 어긋난다.
 *
 * <ol>
 *   <li>{@code classpath:/application.yml} 이 둘이 되어 어느 쪽이 읽힐지가 클래스패스
 *       순서에 달린다. 백엔드의 설정이 시뮬레이터에 걸리면 포트도 키도 틀린다</li>
 *   <li>백엔드의 JPA·Flyway·Security 자동 설정이 시뮬레이터 컨텍스트에도 걸린다.
 *       자동 설정은 컴포넌트 스캔이 아니라 <b>클래스패스</b>가 정하기 때문이다</li>
 * </ol>
 *
 * <p>무엇보다 12주차가 검증하려는 것이 실제 HTTP 왕복·타임아웃·재시도라, 같은 JVM 안의
 * 호출로 대신하면 검증 대상이 사라진다. 클래스패스는 Gradle 이
 * {@code staysync.test.mock-ota-classpath} 로 넘겨 준다.
 *
 * <p><b>시뮬레이터를 고치지 않는다.</b> 어댑터가 시뮬레이터에 맞춘다.
 */
public final class MockOtaProcess implements AutoCloseable {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    private final Process process;
    private final int port;
    private final String apiKey;

    private MockOtaProcess(Process process, int port, String apiKey) {
        this.process = process;
        this.port = port;
        this.apiKey = apiKey;
    }

    /**
     * 띄우고 뜰 때까지 기다린다.
     *
     * @param chaos {@code mockota.chaos.*} 설정. 에러율과 시드를 여기로 준다
     */
    public static MockOtaProcess start(String apiKey, Map<String, String> chaos) {
        String classpath = System.getProperty("staysync.test.mock-ota-classpath");
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException(
                    "시뮬레이터 클래스패스가 없습니다. Gradle 의 test 태스크로 실행해야 합니다.");
        }
        int port = freePort();

        List<String> command = new ArrayList<>(List.of(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-cp", classpath,
                "com.mockota.MockOtaApplication",
                "--server.port=" + port,
                "--mockota.api-key=" + apiKey,
                // 웹훅은 13주차에 쓴다. 지금은 받을 곳이 없어 비워 둔다.
                "--mockota.webhook-url="));
        chaos.forEach((key, value) -> command.add("--mockota.chaos." + key + "=" + value));

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    // 시뮬레이터의 로그를 테스트 출력으로 흘린다. 시드가 여기 찍힌다.
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start();
            MockOtaProcess simulator = new MockOtaProcess(process, port, apiKey);
            simulator.awaitReady();
            return simulator;
        } catch (IOException e) {
            throw new IllegalStateException("시뮬레이터를 띄우지 못했습니다.", e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + port;
    }

    /** 시뮬레이터가 받은 ARI 전문. 우리가 보낸 것을 그대로 돌려준다. */
    public String receivedAri() {
        return get("/api/ari");
    }

    private String get(String path) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(baseUrl() + path))
                            .header("X-Api-Key", apiKey)
                            .timeout(Duration.ofSeconds(10))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "시뮬레이터 조회가 실패했습니다: " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("시뮬레이터에 닿지 못했습니다.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("시뮬레이터 조회가 중단됐습니다.", e);
        }
    }

    /**
     * 뜰 때까지 두드린다.
     *
     * <p>고정 시간을 자면 느린 기계에서 실패하고 빠른 기계에서는 시간을 버린다.
     * 준비됐는지 물어보는 것이 맞다.
     */
    private void awaitReady() {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "시뮬레이터가 기동 중에 죽었습니다. exit=" + process.exitValue());
            }
            try {
                // 키 없이 두드린다. 401 이 오면 살아 있다는 뜻이고, 그거면 충분하다.
                HttpResponse<Void> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(baseUrl() + "/api/bookings"))
                                .timeout(Duration.ofSeconds(2))
                                .GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() > 0) {
                    return;
                }
            } catch (IOException e) {
                sleepBriefly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("시뮬레이터 기동 대기가 중단됐습니다.", e);
            }
        }
        process.destroyForcibly();
        throw new IllegalStateException("시뮬레이터가 " + STARTUP_TIMEOUT + " 안에 뜨지 않았습니다.");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** OS 가 비어 있다고 알려 준 포트. 고정 포트는 다른 프로세스와 부딪힌다. */
    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("빈 포트를 찾지 못했습니다.", e);
        }
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
