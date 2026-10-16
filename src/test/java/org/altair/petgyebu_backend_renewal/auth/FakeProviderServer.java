package org.altair.petgyebu_backend_renewal.auth;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

// 카카오 API·구글 JWKS를 흉내 내는 테스트용 HTTP 서버. 경로와 관계없이 설정한 응답을 돌려준다.
final class FakeProviderServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile Duration delay = Duration.ZERO;
    private volatile String lastAuthorization;
    private volatile String lastPath;

    private FakeProviderServer(HttpServer server) {
        this.server = server;
    }

    static FakeProviderServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeProviderServer fake = new FakeProviderServer(server);
        server.createContext("/", exchange -> {
            fake.requests.incrementAndGet();
            fake.lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            fake.lastPath = exchange.getRequestURI().getPath();
            try {
                Thread.sleep(fake.delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = fake.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(fake.status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        return fake;
    }

    void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    void delay(Duration delay) {
        this.delay = delay;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    String lastAuthorization() {
        return lastAuthorization;
    }

    String lastPath() {
        return lastPath;
    }

    int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
