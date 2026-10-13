package com.hpj.admin.monitor;

import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class OwnedRedisProxyTest {
    @Test void forwardsNativeBytesCutsExistingSocketsAndRebindsSameEndpoint() throws Exception {
        try (var upstream = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
             var proxy = new OwnedRedisProxy(new InetSocketAddress("127.0.0.1", upstream.getLocalPort()))) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var echo = executor.submit(() -> {
                    for (int i = 0; i < 2; i++) try (Socket peer = upstream.accept()) {
                        peer.setSoTimeout(3000); byte[] bytes = peer.getInputStream().readNBytes(4);
                        peer.getOutputStream().write(bytes); peer.getOutputStream().flush();
                        assertThat(peer.getInputStream().read()).isEqualTo(-1); // Remains open until the proxy/client actually cuts it.
                    } return true;
                });
                int stable = proxy.port();
                try (var first = connect(stable)) {
                    exchange(first); proxy.disconnect();
                    assertThat(proxy.connected()).isFalse();
                    assertThatThrownBy(() -> connect(stable)).isInstanceOf(java.io.IOException.class);
                    assertThat(first.getInputStream().read()).isEqualTo(-1);
                }
                proxy.reconnect(); assertThat(proxy.port()).isEqualTo(stable);
                try (var recovered = connect(stable)) { exchange(recovered); }
                assertThat(echo.get(5, TimeUnit.SECONDS)).isTrue();
            } finally { executor.shutdownNow(); }
        }
    }
    @Test void rejectsNonLoopbackDestinations() {
        assertThatThrownBy(() -> new OwnedRedisProxy(new InetSocketAddress("192.0.2.1", 6379))).isInstanceOf(IllegalArgumentException.class);
    }
    private Socket connect(int port) throws Exception {
        var socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", port), 1000); socket.setSoTimeout(3000); return socket;
    }
    private void exchange(Socket socket) throws Exception {
        socket.getOutputStream().write("PING".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
        assertThat(new String(socket.getInputStream().readNBytes(4), StandardCharsets.US_ASCII)).isEqualTo("PING");
    }
}
