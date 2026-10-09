package com.lumenmusic;

import com.sun.jna.CallbackReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.platform.win32.WinDef.HICON;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WinUser.WindowProc;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import java.util.Arrays;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Native Windows thumbnail-toolbar controls for the JavaFX main window. All public methods run on the FX thread. */
public final class WindowsTaskbarControls implements AutoCloseable {
    private static final int WM_COMMAND = 0x0111;
    private static final int THBN_CLICKED = 0x1800;
    private static final int GWL_WNDPROC = -4;
    private static final int THB_ICON = 0x2;
    private static final int THB_TOOLTIP = 0x4;
    private static final int THB_FLAGS = 0x8;
    private static final int PREVIOUS_ID = 41;
    private static final int TOGGLE_ID = 42;
    private static final int NEXT_ID = 43;
    private static final int FAVORITE_ID = 44;
    private static final int MAX_ATTEMPTS = 12;
    private static final GUID CLSID_TASKBAR_LIST = new GUID("{56FDF344-FD6D-11D0-958A-006097C9A090}");
    private static final GUID IID_TASKBAR_LIST3 = new GUID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}");

    private final Stage stage;
    private final Runnable previous;
    private final Runnable toggle;
    private final Runnable next;
    private final Runnable favorite;
    private HWND hwnd;
    private Pointer oldWindowProc;
    private WindowProc windowProc;
    private HICON previousIcon;
    private HICON playIcon;
    private HICON pauseIcon;
    private HICON nextIcon;
    private HICON favoriteOutlineIcon;
    private HICON favoriteFilledIcon;
    private int taskbarButtonCreated;
    private int attempts;
    private boolean installed;
    private boolean playing;
    private boolean favoriteSelected;
    private boolean closed;
    private PauseTransition retry;
    private String lastError = "尚未连接窗口";

    private WindowsTaskbarControls(Stage stage, Runnable previous, Runnable toggle, Runnable next, Runnable favorite) {
        this.stage = Objects.requireNonNull(stage);
        this.previous = Objects.requireNonNull(previous);
        this.toggle = Objects.requireNonNull(toggle);
        this.next = Objects.requireNonNull(next);
        this.favorite = Objects.requireNonNull(favorite);
    }

    /** Call on the FX thread, preferably before Stage.show(). Returns null on non-Windows systems. */
    public static WindowsTaskbarControls attach(Stage stage, Runnable previous, Runnable toggle, Runnable next) {
        return attach(stage, previous, toggle, next, () -> {});
    }

    /** Call on the FX thread, preferably before Stage.show(). Returns null on non-Windows systems. */
    public static WindowsTaskbarControls attach(Stage stage, Runnable previous, Runnable toggle, Runnable next,
            Runnable favorite) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) return null;
        requireFxThread();
        WindowsTaskbarControls controls = new WindowsTaskbarControls(stage, previous, toggle, next, favorite);
        stage.showingProperty().addListener((ignored, wasShowing, showing) -> {
            if (showing) Platform.runLater(controls::connect);
        });
        if (stage.isShowing()) Platform.runLater(controls::connect);
        return controls;
    }

    /** Refreshes the thumbnail play/pause icon and tooltip after the shared player state changes. */
    public void setPlaying(boolean playing) {
        requireFxThread();
        this.playing = playing;
        if (installed) updateToggleButton();
    }

    /** Refreshes the thumbnail favorite icon and tooltip after the current track changes. */
    public void setFavorite(boolean favoriteSelected) {
        requireFxThread();
        this.favoriteSelected = favoriteSelected;
        if (installed) updateFavoriteButton();
    }

    public boolean isInstalled() { return installed; }
    public String lastError() { return lastError; }

    private void connect() {
        if (closed || !stage.isShowing()) return;
        HWND visibleWindow = findStageWindow(stage);
        if (visibleWindow == null) { lastError = "找不到当前进程的 JavaFX 窗口"; scheduleRetry(); return; }
        if (hwnd == null || !hwnd.equals(visibleWindow)) {
            hwnd = visibleWindow;
            oldWindowProc = null;
            windowProc = null;
            installed = false;
        }
        if (installed) return;
        if (windowProc == null) {
            taskbarButtonCreated = User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated");
            windowProc = (window, message, wParam, lParam) -> {
                if (message == WM_COMMAND && highWord(wParam.longValue()) == THBN_CLICKED) {
                    Runnable command = commandFor(lowWord(wParam.longValue()), previous, toggle, next, favorite);
                    if (command != null) {
                        Platform.runLater(command);
                        return new LRESULT(0);
                    }
                }
                if (message == taskbarButtonCreated) {
                    installed = false;
                    Platform.runLater(this::addButtons);
                }
                return User32.INSTANCE.CallWindowProc(oldWindowProc, window, message, wParam, lParam);
            };
            oldWindowProc = User32.INSTANCE.SetWindowLongPtr(hwnd, GWL_WNDPROC,
                    CallbackReference.getFunctionPointer(windowProc));
            if (oldWindowProc == null) {
                lastError = "无法挂接 Win32 窗口消息，错误码 " + Native.getLastError();
                windowProc = null;
                scheduleRetry();
                return;
            }
        }
        addButtons();
    }

    private void addButtons() {
        if (closed || hwnd == null || installed || !stage.isShowing()) return;
        try {
            if (previousIcon == null) previousIcon = makeIcon(IconKind.PREVIOUS);
            if (playIcon == null) playIcon = makeIcon(IconKind.PLAY);
            if (pauseIcon == null) pauseIcon = makeIcon(IconKind.PAUSE);
            if (nextIcon == null) nextIcon = makeIcon(IconKind.NEXT);
            if (favoriteOutlineIcon == null) favoriteOutlineIcon = makeIcon(IconKind.FAVORITE_OUTLINE);
            if (favoriteFilledIcon == null) favoriteFilledIcon = makeIcon(IconKind.FAVORITE_FILLED);
            ThumbButton[] buttons = (ThumbButton[]) new ThumbButton().toArray(4);
            configure(buttons[0], PREVIOUS_ID, previousIcon, "上一首");
            configure(buttons[1], TOGGLE_ID, playing ? pauseIcon : playIcon, playing ? "暂停" : "播放");
            configure(buttons[2], NEXT_ID, nextIcon, "下一首");
            configureFavorite(buttons[3], favoriteSelected, favoriteOutlineIcon, favoriteFilledIcon);
            Arrays.stream(buttons).forEach(Structure::write);
            HRESULT result = withTaskbar(taskbar -> taskbar.call(15, hwnd, 4, buttons[0].getPointer()));
            if (failed(result)) {
                lastError = "ThumbBarAddButtons 返回 " + String.format("0x%08X", result.intValue());
                scheduleRetry(); return;
            }
            installed = true;
            attempts = 0;
            lastError = "已安装";
        } catch (RuntimeException ex) {
            lastError = ex.toString();
            scheduleRetry();
        }
    }

    private void updateToggleButton() {
        updateButton(TOGGLE_ID, playing ? pauseIcon : playIcon, playing ? "暂停" : "播放");
    }

    private void updateFavoriteButton() {
        if (closed || hwnd == null) return;
        try {
            ThumbButton button = new ThumbButton();
            configureFavorite(button, favoriteSelected, favoriteOutlineIcon, favoriteFilledIcon);
            updateButton(button);
        } catch (RuntimeException ex) {
            installed = false;
            lastError = ex.toString();
            scheduleRetry();
        }
    }

    private void updateButton(int id, HICON icon, String tooltip) {
        if (closed || hwnd == null) return;
        try {
            ThumbButton button = new ThumbButton();
            configure(button, id, icon, tooltip);
            updateButton(button);
        } catch (RuntimeException ex) {
            installed = false;
            lastError = ex.toString();
            scheduleRetry();
        }
    }

    private void updateButton(ThumbButton button) {
        button.write();
        HRESULT result = withTaskbar(taskbar -> taskbar.call(16, hwnd, 1, button.getPointer()));
        if (failed(result)) {
            installed = false;
            lastError = "ThumbBarUpdateButtons 返回 " + String.format("0x%08X", result.intValue());
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (closed || attempts++ >= MAX_ATTEMPTS) return;
        if (retry != null) retry.stop();
        retry = new PauseTransition(Duration.millis(350));
        retry.setOnFinished(ignored -> connect());
        retry.play();
    }

    @Override public void close() {
        requireFxThread();
        if (closed) return;
        closed = true;
        if (retry != null) retry.stop();
        if (hwnd != null && oldWindowProc != null && User32.INSTANCE.IsWindow(hwnd)) {
            User32.INSTANCE.SetWindowLongPtr(hwnd, GWL_WNDPROC, oldWindowProc);
        }
        for (HICON icon : new HICON[] { previousIcon, playIcon, pauseIcon, nextIcon,
                favoriteOutlineIcon, favoriteFilledIcon }) {
            if (icon != null) User32.INSTANCE.DestroyIcon(icon);
        }
        windowProc = null;
        installed = false;
    }

    static Runnable commandFor(int id, Runnable previous, Runnable toggle, Runnable next, Runnable favorite) {
        return switch (id) {
            case PREVIOUS_ID -> previous;
            case TOGGLE_ID -> toggle;
            case NEXT_ID -> next;
            case FAVORITE_ID -> favorite;
            default -> null;
        };
    }

    static int lowWord(long value) { return (int) (value & 0xffff); }
    static int highWord(long value) { return (int) ((value >>> 16) & 0xffff); }

    private static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("Requires JavaFX application thread");
    }

    private static HWND findStageWindow(Stage stage) {
        HWND[] found = new HWND[1];
        long pid = ProcessHandle.current().pid();
        User32.INSTANCE.EnumWindows((window, ignored) -> {
            if (!User32.INSTANCE.IsWindowVisible(window)) return true;
            IntByReference windowPid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, windowPid);
            if (Integer.toUnsignedLong(windowPid.getValue()) != pid) return true;
            char[] title = new char[512];
            User32.INSTANCE.GetWindowText(window, title, title.length);
            String text = Native.toString(title);
            if (!stage.getTitle().equals(text)) return true;
            found[0] = window;
            return false;
        }, null);
        return found[0];
    }

    private static void configure(ThumbButton button, int id, HICON icon, String tooltip) {
        button.dwMask = THB_ICON | THB_TOOLTIP | THB_FLAGS;
        button.iId = id;
        button.hIcon = icon;
        Arrays.fill(button.szTip, '\0');
        tooltip.getChars(0, Math.min(tooltip.length(), 259), button.szTip, 0);
        button.dwFlags = 0;
    }

    static void configureFavorite(ThumbButton button, boolean selected, HICON outlineIcon, HICON filledIcon) {
        configure(button, FAVORITE_ID, selected ? filledIcon : outlineIcon, selected ? "取消收藏" : "收藏");
    }

    private static HICON makeIcon(IconKind kind) {
        IconBits bits = iconBits(kind);
        HICON icon = User32Icons.INSTANCE.CreateIcon(null, 16, 16, (byte) 1, (byte) 32,
                bits.andBits(), bits.xorBits());
        if (icon == null) throw new IllegalStateException("Cannot create taskbar icon");
        return icon;
    }

    static IconBits iconBits(IconKind kind) {
        byte[] andBits = new byte[32];
        Arrays.fill(andBits, (byte) 0xff);
        byte[] xorBits = new byte[16 * 16 * 4];
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            boolean on = switch (kind) {
                case PLAY -> x >= 4 && x <= 11 && y >= 3 && y <= 12
                        && Math.abs(y - 7) * 7 <= (11 - x) * 5;
                case PAUSE -> y >= 3 && y <= 12 && (x >= 4 && x <= 6 || x >= 9 && x <= 11);
                case PREVIOUS -> y >= 3 && y <= 12 && (x == 3 || x >= 4 && x <= 11
                        && Math.abs(y - 7) * 7 <= (x - 4) * 5);
                case NEXT -> y >= 3 && y <= 12 && (x == 12 || x >= 4 && x <= 11
                        && Math.abs(y - 7) * 7 <= (11 - x) * 5);
                case FAVORITE_FILLED -> heartContains(x, y);
                case FAVORITE_OUTLINE -> heartContains(x, y) && (!heartContains(x - 1, y)
                        || !heartContains(x + 1, y) || !heartContains(x, y - 1) || !heartContains(x, y + 1));
            };
            if (!on) continue;
            // CreateIcon consumes raw bitmap scanlines from top to bottom.
            int offset = (y * 16 + x) * 4;
            xorBits[offset] = xorBits[offset + 1] = xorBits[offset + 2] = xorBits[offset + 3] = (byte) 0xff;
            int maskOffset = y * 2 + x / 8;
            andBits[maskOffset] &= (byte) ~(0x80 >>> (x % 8));
        }
        return new IconBits(andBits, xorBits);
    }

    private static boolean heartContains(int x, int y) {
        return switch (y) {
            case 2 -> x >= 3 && x <= 5 || x >= 10 && x <= 12;
            case 3 -> x >= 2 && x <= 6 || x >= 9 && x <= 13;
            case 4, 5 -> x >= 1 && x <= 14;
            case 6, 7 -> x >= 2 && x <= 13;
            case 8 -> x >= 3 && x <= 12;
            case 9 -> x >= 4 && x <= 11;
            case 10 -> x >= 5 && x <= 10;
            case 11 -> x >= 6 && x <= 9;
            case 12 -> x >= 7 && x <= 8;
            default -> false;
        };
    }

    private static HRESULT withTaskbar(TaskbarCall call) {
        HRESULT init = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED);
        boolean uninit = init.intValue() >= 0;
        if (!uninit && init.intValue() != 0x80010106) return init;
        PointerByReference pointer = new PointerByReference();
        try {
            HRESULT created = Ole32.INSTANCE.CoCreateInstance(CLSID_TASKBAR_LIST, null,
                    WTypes.CLSCTX_INPROC_SERVER, IID_TASKBAR_LIST3, pointer);
            if (failed(created)) return created;
            TaskbarList3 taskbar = new TaskbarList3(pointer.getValue());
            try {
                HRESULT hrInit = taskbar.call(3);
                return failed(hrInit) ? hrInit : call.run(taskbar);
            } finally {
                taskbar.Release();
            }
        } finally {
            if (uninit) Ole32.INSTANCE.CoUninitialize();
        }
    }

    private static boolean failed(HRESULT result) { return result == null || result.intValue() < 0; }
    enum IconKind { PREVIOUS, PLAY, PAUSE, NEXT, FAVORITE_OUTLINE, FAVORITE_FILLED }
    record IconBits(byte[] andBits, byte[] xorBits) {}
    private interface TaskbarCall { HRESULT run(TaskbarList3 taskbar); }
    private interface User32Icons extends StdCallLibrary {
        User32Icons INSTANCE = Native.load("user32", User32Icons.class);
        HICON CreateIcon(HINSTANCE instance, int width, int height, byte planes, byte bitsPerPixel,
                byte[] andBits, byte[] xorBits);
    }
    private static final class TaskbarList3 extends Unknown {
        TaskbarList3(Pointer pointer) { super(pointer); }
        HRESULT call(int slot, Object... args) {
            Object[] parameters = new Object[args.length + 1];
            parameters[0] = getPointer();
            System.arraycopy(args, 0, parameters, 1, args.length);
            return (HRESULT) _invokeNativeObject(slot, parameters, HRESULT.class);
        }
    }
    @Structure.FieldOrder({"dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags"})
    public static class ThumbButton extends Structure {
        public int dwMask;
        public int iId;
        public int iBitmap;
        public HICON hIcon;
        public char[] szTip = new char[260];
        public int dwFlags;
    }
}
