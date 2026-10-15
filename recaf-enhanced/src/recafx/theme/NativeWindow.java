package recafx.theme;

import javafx.stage.Stage;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * Windows 11 DWM window-corner rounding through the foreign function API.
 */
public final class NativeWindow {
	private static final Logger logger = Logging.get(NativeWindow.class);
	private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
	private static MethodHandle dwmSetWindowAttribute;
	private static boolean unavailable;

	private NativeWindow() {}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	/** @param rounded {@code true} for rounded corners (DWMWCP_ROUND), {@code false} for square (DWMWCP_DONOTROUND). */
	public static void setRoundedCorners(Stage stage, boolean rounded) {
		if (!isWindows() || unavailable) return;
		try {
			long hwnd = hwndOf(stage);
			if (hwnd == 0) return;
			MethodHandle handle = handle();
			if (handle == null) return;
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment pref = arena.allocate(ValueLayout.JAVA_INT);
				pref.set(ValueLayout.JAVA_INT, 0, rounded ? 2 : 1);
				int hr = (int) handle.invoke(MemorySegment.ofAddress(hwnd), DWMWA_WINDOW_CORNER_PREFERENCE, pref, 4);
				if (hr != 0) logger.debug("DwmSetWindowAttribute returned 0x{}", Integer.toHexString(hr));
				// Keep the native caption consistent with the dark JavaFX surface.
				pref.set(ValueLayout.JAVA_INT, 0, 1);
				int captionResult = (int) handle.invoke(MemorySegment.ofAddress(hwnd), 20, pref, 4);
				if (captionResult != 0) logger.debug("Dark caption unavailable: 0x{}", Integer.toHexString(captionResult));
			}
		} catch (Throwable t) {
			unavailable = true;
			logger.debug("Window corner rounding unavailable: {}", t.toString());
		}
	}

	private static synchronized MethodHandle handle() {
		if (dwmSetWindowAttribute != null) return dwmSetWindowAttribute;
		try {
			SymbolLookup dwm = SymbolLookup.libraryLookup("dwmapi.dll", Arena.global());
			MemorySegment symbol = dwm.find("DwmSetWindowAttribute").orElse(null);
			if (symbol == null) return null;
			dwmSetWindowAttribute = Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT,
					ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
			return dwmSetWindowAttribute;
		} catch (Throwable t) {
			unavailable = true;
			return null;
		}
	}

	/** Resolves the HWND of a stage through JavaFX's internal peer classes (reflection, no compile-time dependency). */
	public static long hwndOf(Stage stage) {
		try {
			Class<?> helper = Class.forName("com.sun.javafx.stage.WindowHelper");
			Method getPeer = helper.getMethod("getPeer", javafx.stage.Window.class);
			Object tkStage = getPeer.invoke(null, stage);
			if (tkStage == null) return 0;
			Method getPlatformWindow = tkStage.getClass().getMethod("getPlatformWindow");
			getPlatformWindow.setAccessible(true);
			Object glassWindow = getPlatformWindow.invoke(tkStage);
			if (glassWindow == null) return 0;
			Method getNativeWindow = glassWindow.getClass().getMethod("getNativeWindow");
			getNativeWindow.setAccessible(true);
			Object value = getNativeWindow.invoke(glassWindow);
			return value instanceof Long l ? l : 0;
		} catch (Throwable t) {
			logger.debug("Could not resolve HWND: {}", t.toString());
			return 0;
		}
	}
}
