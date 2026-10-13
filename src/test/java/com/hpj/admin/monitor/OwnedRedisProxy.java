package com.hpj.admin.monitor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Test-only byte forwarding. No Redis replies are fabricated; both endpoints are owned loopback sockets. */
final class OwnedRedisProxy implements AutoCloseable {
    private final InetSocketAddress upstream;
    private final Set<Socket> peers = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 32, 10, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> { var thread = new Thread(runnable, "owned-redis-forwarder"); thread.setDaemon(true); return thread; });
    private ServerSocket listener;
    private final int port;
    private boolean closed;

    OwnedRedisProxy(InetSocketAddress upstream) throws IOException {
        if (upstream.isUnresolved() || !upstream.getAddress().isLoopbackAddress()) throw new IllegalArgumentException("Owned loopback endpoint required");
        this.upstream = upstream;
        listener = bind(0); port = listener.getLocalPort(); accept(listener);
    }
    int port() { return port; }
    private ServerSocket bind(int selected) throws IOException {
        var server = new ServerSocket(); server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), selected)); return server;
    }
    private void accept(ServerSocket server) {
        // The listener has its own daemon so long-lived forwarding pairs cannot starve accept/rebind.
        var thread = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket incoming = server.accept();
                    synchronized (this) {
                        if (closed || listener != server || peers.size() >= 24) { incoming.close(); continue; }
                        peers.add(incoming);
                    }
                    try { workers.execute(() -> forward(incoming, server)); }
                    catch (RuntimeException rejected) { release(incoming); }
                } catch (IOException stopped) { break; }
            }
        }, "owned-redis-listener"); thread.setDaemon(true); thread.start();
    }
    private void forward(Socket incoming, ServerSocket server) {
        var outgoing = new Socket();
        try {
            synchronized (this) {
                if (closed || listener != server) return;
                peers.add(outgoing);
            }
            outgoing.connect(upstream, 3000);
            incoming.setTcpNoDelay(true); outgoing.setTcpNoDelay(true);
            workers.execute(() -> pump(outgoing, incoming));
            pump(incoming, outgoing);
        } catch (IOException | RuntimeException unavailable) {
            // Deliberate cut/rebind closes only these owned sockets.
        } finally { release(incoming); release(outgoing); }
    }
    private void pump(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch (IOException stopped) { }
        finally { release(from); release(to); }
    }
    private synchronized void release(Socket socket) {
        peers.remove(socket); try { socket.close(); } catch (IOException ignored) { }
    }
    synchronized void disconnect() throws IOException {
        if (listener != null) { listener.close(); listener = null; }
        for (Socket socket : Set.copyOf(peers)) release(socket);
    }
    synchronized void reconnect() throws IOException {
        if (closed) throw new IllegalStateException("Owned proxy closed");
        if (listener == null) { listener = bind(port); accept(listener); }
    }
    synchronized boolean connected() { return listener != null && !listener.isClosed(); }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true; disconnect(); workers.shutdownNow();
    }
}
