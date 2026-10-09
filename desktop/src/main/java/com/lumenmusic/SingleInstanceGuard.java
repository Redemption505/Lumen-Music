package com.lumenmusic;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cross-JVM guard for the packaged desktop application. */
final class SingleInstanceGuard implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;
    private final Thread listener;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SingleInstanceGuard(FileChannel channel, FileLock lock, ServerSocket server) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
        this.listener = new Thread(this::listen, "lumen-single-instance");
        listener.setDaemon(true);
        listener.start();
    }

    /** Returns the owning guard, or null after asking an existing instance to activate. */
    static SingleInstanceGuard acquire(Path stateDirectory) throws IOException {
        Files.createDirectories(stateDirectory);
        Path stateFile = stateDirectory.resolve("instance.lock");
        Path endpointFile = stateDirectory.resolve("instance.endpoint");
        FileChannel channel = FileChannel.open(stateFile, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock lock;
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException busy) { lock = null; }
        if (lock != null) {
            try {
                ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
                try {
                    byte[] data = (ProcessHandle.current().pid() + "\n" + server.getLocalPort())
                            .getBytes(StandardCharsets.UTF_8);
                    Files.write(endpointFile, data);
                    return new SingleInstanceGuard(channel, lock, server);
                } catch (IOException | RuntimeException error) {
                    server.close();
                    throw error;
                }
            } catch (IOException | RuntimeException error) {
                lock.release();
                channel.close();
                throw error;
            }
        }
        channel.close();
        signalOwner(endpointFile);
        return null;
    }

    private static void signalOwner(Path stateFile) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                String[] fields = Files.readString(stateFile, StandardCharsets.UTF_8).trim().split("\\R");
                if (fields.length >= 2) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                                Integer.parseInt(fields[1])), 250);
                        socket.getOutputStream().write(1);
                        socket.getOutputStream().flush();
                        return;
                    }
                }
            } catch (IOException | NumberFormatException error) {
                if (error instanceof IOException io) last = io;
            }
            try { Thread.sleep(40); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        if (last != null) throw new IOException("已有 Lumen Music 实例未能接收唤醒请求", last);
        throw new IOException("已有 Lumen Music 实例未能接收唤醒请求");
    }

    private void listen() {
        while (!closed.get()) {
            try (Socket ignored = server.accept()) { activateWindow(); }
            catch (IOException stopped) { if (!closed.get()) stopped.printStackTrace(System.err); }
        }
    }

    private static void activateWindow() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        int currentPid = Math.toIntExact(ProcessHandle.current().pid());
        User32.INSTANCE.EnumWindows((window, ignored) -> {
            IntByReference owner = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, owner);
            if (owner.getValue() != currentPid || !User32.INSTANCE.IsWindowVisible(window)) return true;
            User32.INSTANCE.ShowWindow(window, WinUser.SW_RESTORE);
            User32.INSTANCE.SetForegroundWindow(window);
            return false;
        }, null);
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { server.close(); } catch (IOException ignored) { }
        try { listener.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        try { lock.release(); } catch (IOException ignored) { }
        try { channel.close(); } catch (IOException ignored) { }
    }
}
