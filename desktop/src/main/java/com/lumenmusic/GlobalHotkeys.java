package com.lumenmusic;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/** RegisterHotKey and its message queue belong to one dedicated native thread. */
public final class GlobalHotkeys implements AutoCloseable {
    public enum Action { PREVIOUS, TOGGLE, NEXT, VOLUME_UP, VOLUME_DOWN }
    public static final int MOD_ALT = 1, MOD_CONTROL = 2, MOD_SHIFT = 4, MOD_WIN = 8;
    private static final int WM_HOTKEY = 0x0312, WM_APPLY = 0x8001, WM_CLOSE = 0x8002;
    private static final int FIRST_ID = 0x5100, MOD_NOREPEAT = 0x4000;

    public record Combination(int modifiers, int virtualKey) {
        public Combination {
            if ((modifiers & ~15) != 0 || virtualKey < 1 || virtualKey > 254
                    || virtualKey == 16 || virtualKey == 17 || virtualKey == 18
                    || virtualKey == 91 || virtualKey == 92)
                throw new IllegalArgumentException("Invalid hotkey combination");
            if (modifiers == 0 && !(virtualKey >= 112 && virtualKey <= 135))
                throw new IllegalArgumentException("Use a modifier or an F1-F24 key");
        }
        public String displayName() {
            StringBuilder name = new StringBuilder();
            if ((modifiers & MOD_CONTROL) != 0) name.append("Ctrl + ");
            if ((modifiers & MOD_ALT) != 0) name.append("Alt + ");
            if ((modifiers & MOD_SHIFT) != 0) name.append("Shift + ");
            if ((modifiers & MOD_WIN) != 0) name.append("Win + ");
            String key = switch (virtualKey) {
                case 32 -> "Space"; case 37 -> "Left"; case 38 -> "Up";
                case 39 -> "Right"; case 40 -> "Down"; case 13 -> "Enter";
                case 27 -> "Esc"; case 9 -> "Tab"; case 8 -> "Backspace";
                case 46 -> "Delete"; case 36 -> "Home"; case 35 -> "End";
                case 33 -> "Page Up"; case 34 -> "Page Down";
                case 186 -> ";"; case 187 -> "="; case 188 -> ","; case 189 -> "-";
                case 190 -> "."; case 191 -> "/"; case 192 -> "`";
                case 219 -> "["; case 220 -> "\\"; case 221 -> "]"; case 222 -> "'";
                default -> virtualKey >= 112 && virtualKey <= 135 ? "F" + (virtualKey - 111)
                        : virtualKey >= 48 && virtualKey <= 90 ? Character.toString((char) virtualKey)
                        : "VK " + virtualKey;
            };
            return name.append(key).toString();
        }
    }

    public static Map<Action, Combination> defaults() {
        int modifiers = MOD_CONTROL | MOD_ALT;
        return Map.of(Action.PREVIOUS, new Combination(modifiers, 37),
                Action.TOGGLE, new Combination(modifiers, 32),
                Action.NEXT, new Combination(modifiers, 39),
                Action.VOLUME_UP, new Combination(modifiers, 38),
                Action.VOLUME_DOWN, new Combination(modifiers, 40));
    }

    /** Converts JavaFX punctuation codes as well as letters, arrows and function keys to Win32 VKs. */
    public static Optional<Combination> fromKeyEvent(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code.isModifierKey() || code == KeyCode.UNDEFINED) return Optional.empty();
        int vk = switch (code) {
            case ENTER -> 13; case DELETE -> 46; case INSERT -> 45; case HELP -> 47;
            case F13 -> 124; case F14 -> 125; case F15 -> 126; case F16 -> 127;
            case F17 -> 128; case F18 -> 129; case F19 -> 130; case F20 -> 131;
            case F21 -> 132; case F22 -> 133; case F23 -> 134; case F24 -> 135;
            case SEMICOLON -> 186; case EQUALS, PLUS -> 187; case COMMA -> 188;
            case MINUS -> 189; case PERIOD -> 190; case SLASH -> 191;
            case BACK_QUOTE -> 192; case OPEN_BRACKET -> 219; case BACK_SLASH -> 220;
            case CLOSE_BRACKET -> 221; case QUOTE -> 222;
            default -> code.getCode();
        };
        int modifiers = (event.isAltDown() ? MOD_ALT : 0)
                | (event.isControlDown() ? MOD_CONTROL : 0)
                | (event.isShiftDown() ? MOD_SHIFT : 0) | (event.isMetaDown() ? MOD_WIN : 0);
        try { return Optional.of(new Combination(modifiers, vk)); }
        catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    private record Request(Map<Action, Combination> values, long version,
                           CompletableFuture<Map<Action, String>> result) { }
    private final Consumer<Action> onAction;
    private final Consumer<String> onError;
    private final Executor dispatch;
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<Integer> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private volatile boolean closed;
    private volatile long requestedVersion;
    private volatile Map<Action, Combination> installed = Map.of();

    public GlobalHotkeys(Consumer<Action> onAction, Consumer<String> onError) {
        this(onAction, onError, Platform::runLater);
    }

    public GlobalHotkeys(Consumer<Action> onAction, Consumer<String> onError, Executor dispatch) {
        this.onAction = Objects.requireNonNull(onAction);
        this.onError = Objects.requireNonNull(onError);
        this.dispatch = Objects.requireNonNull(dispatch);
        Thread loop = new Thread(this::messageLoop, "lumen-global-hotkeys");
        loop.setDaemon(true);
        loop.start();
    }

    /** Empty input unregisters all shortcuts. Conflicts are reported per action. */
    public synchronized CompletableFuture<Map<Action, String>> apply(Map<Action, Combination> values) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Hotkeys are closed"));
        CompletableFuture<Map<Action, String>> result = new CompletableFuture<>();
        requests.add(new Request(Map.copyOf(values), ++requestedVersion, result));
        ready.whenComplete((id, error) -> {
            if (error != null) result.completeExceptionally(error);
            else if (!User32Hotkeys.INSTANCE.PostThreadMessage(id, WM_APPLY, new WPARAM(0), new LPARAM(0)))
                result.completeExceptionally(new IllegalStateException("Unable to wake hotkey thread"));
        });
        return result;
    }

    public Map<Action, Combination> installed() { return installed; }
    public CompletableFuture<Void> termination() { return termination; }

    private void messageLoop() {
        EnumMap<Action, Combination> active = new EnumMap<>(Action.class);
        long activeVersion = 0;
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("windows"))
                throw new IllegalStateException("Global shortcuts require Windows");
            User32Hotkeys user = User32Hotkeys.INSTANCE;
            MSG message = new MSG();
            user.PeekMessage(message, null, 0, 0, 0);
            ready.complete(Kernel32.INSTANCE.GetCurrentThreadId());
            while (!closed) {
                int received = user.GetMessage(message, null, 0, 0);
                if (received == 0 || message.message == WM_CLOSE) break;
                if (received < 0) throw new IllegalStateException("GetMessage failed: " + Native.getLastError());
                if (message.message == WM_HOTKEY) {
                    int index = message.wParam.intValue() - FIRST_ID;
                    if (index >= 0 && index < Action.values().length && active.containsKey(Action.values()[index])) {
                        Action action = Action.values()[index];
                        long eventVersion = activeVersion;
                        dispatch.execute(() -> {
                            if (!closed && requestedVersion == eventVersion) onAction.accept(action);
                        });
                    }
                } else if (message.message == WM_APPLY) {
                    for (Request request; (request = requests.poll()) != null;) {
                        unregister(user, active);
                        EnumMap<Action, String> failures = new EnumMap<>(Action.class);
                        Map<Combination, Action> combinations = new HashMap<>();
                        for (Action action : Action.values()) {
                            Combination value = request.values().get(action);
                            if (value == null) continue;
                            Action duplicate = combinations.putIfAbsent(value, action);
                            if (duplicate != null) failures.put(action, value.displayName() + " 已分配给 " + duplicate);
                            else if (!user.RegisterHotKey(null, FIRST_ID + action.ordinal(),
                                    value.modifiers() | MOD_NOREPEAT, value.virtualKey()))
                                failures.put(action, value.displayName() + " 被系统或其他程序占用，错误码 " + Native.getLastError());
                            else active.put(action, value);
                        }
                        installed = Map.copyOf(active);
                        activeVersion = request.version();
                        request.result().complete(Map.copyOf(failures));
                    }
                }
            }
        } catch (Throwable error) {
            ready.completeExceptionally(error);
            dispatch.execute(() -> onError.accept(error.getMessage()));
        } finally {
            closed = true;
            if (!active.isEmpty()) unregister(User32Hotkeys.INSTANCE, active);
            installed = Map.of();
            for (Request request; (request = requests.poll()) != null;)
                request.result().completeExceptionally(new IllegalStateException("Hotkey loop stopped"));
            termination.complete(null);
        }
    }

    private static void unregister(User32Hotkeys user, Map<Action, Combination> active) {
        for (Action action : active.keySet()) user.UnregisterHotKey(null, FIRST_ID + action.ordinal());
        active.clear();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        ready.thenAccept(id -> User32Hotkeys.INSTANCE.PostThreadMessage(id, WM_CLOSE, new WPARAM(0), new LPARAM(0)));
    }

    private interface User32Hotkeys extends StdCallLibrary {
        User32Hotkeys INSTANCE = Native.load("user32", User32Hotkeys.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean RegisterHotKey(HWND window, int id, int modifiers, int key);
        boolean UnregisterHotKey(HWND window, int id);
        boolean PeekMessage(MSG message, HWND window, int min, int max, int remove);
        int GetMessage(MSG message, HWND window, int min, int max);
        boolean PostThreadMessage(int thread, int message, WPARAM wParam, LPARAM lParam);
    }
}
