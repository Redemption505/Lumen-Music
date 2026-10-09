package com.lumenmusic;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class MusicClientRuntimeTest {
    private static final Track TRACK = new Track("bilibili:BV1nhMuzaEJr:p1", "Fixture", "Fixture", 10,
            "bilibili", null, "BV1nhMuzaEJr", 1);

    @Test void clientCreatedBeforeSidecarReadyUsesItsLatestEndpointAndRetainsPythonFallback() throws Exception {
        AtomicInteger pythonCalls = new AtomicInteger();
        AtomicInteger proxyCalls = new AtomicInteger();
        HttpServer python = streamServer(pythonCalls);
        HttpServer proxy = streamServer(proxyCalls);
        String previous = System.getProperty("lumen.stream.base");
        try {
            System.clearProperty("lumen.stream.base");
            MusicClient client = new MusicClient(base(python));
            assertEquals(base(python).getPort(), client.checkStream(TRACK).getPort());
            System.setProperty("lumen.stream.base", base(proxy).toString());
            assertEquals(base(proxy).getPort(), client.checkStream(TRACK).getPort());
            System.clearProperty("lumen.stream.base");
            assertEquals(base(python).getPort(), client.checkStream(TRACK).getPort());
            assertEquals(2, pythonCalls.get());
            assertEquals(1, proxyCalls.get());
        } finally {
            python.stop(0);
            proxy.stop(0);
            if (previous == null) System.clearProperty("lumen.stream.base");
            else System.setProperty("lumen.stream.base", previous);
        }
    }

    private static HttpServer streamServer(AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracks/", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "audio/mp4");
            exchange.getResponseHeaders().set("Content-Length", "100");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static URI base(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
