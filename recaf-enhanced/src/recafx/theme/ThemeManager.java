package recafx.theme;

import javafx.animation.PauseTransition;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.collections.ListChangeListener;
import javafx.beans.value.ChangeListener;
import javafx.util.Duration;
import org.slf4j.Logger;
import recafx.util.Fx;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.window.WindowManager;
import software.coley.recaf.services.window.WindowStyling;
import software.coley.recaf.util.FxThreadUtil;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Turns {@link ThemeConfig} into a stylesheet (plus the animated canvas behind the main window) and keeps
 * every open window in sync.
 */
public class ThemeManager {
	private static final Logger logger = Logging.get(ThemeManager.class);
	private final ThemeConfig config;
	private final WindowManager windowManager;
	private final WindowStyling windowStyling;
	private final RecafDirectoriesConfig directories;
	private final AnimatedBackground canvas = new AnimatedBackground();
	private final PauseTransition debounce = new PauseTransition(Duration.millis(120));
	private final List<String> previousSheets = new ArrayList<>();
	private Stage mainStage;
	private StackPane stack;
	private int version;
	private String currentSheet;
	private Path sheetPath;
	private final Map<Scene, MonochromeIcons> iconScopes = new IdentityHashMap<>();
	private final Map<Scene, ChangeListener<Parent>> rootListeners = new IdentityHashMap<>();
	private final Map<Window, ChangeListener<Scene>> sceneListeners = new IdentityHashMap<>();
	private final ListChangeListener<Window> windowListener = change -> {
		while (change.next()) {
			change.getRemoved().forEach(this::detach);
			change.getAddedSubList().forEach(this::attach);
		}
	};

	public ThemeManager(ThemeConfig config, WindowManager windowManager, WindowStyling windowStyling, RecafDirectoriesConfig directories) {
		this.config = config;
		this.windowManager = windowManager;
		this.windowStyling = windowStyling;
		this.directories = directories;
		debounce.setOnFinished(e -> apply());
	}

	/** Must run on the FX thread once the main window exists. */
	public void install(Stage mainStage) {
		this.mainStage = mainStage;
		URL base = ThemeManager.class.getResource("/recafx/base.css");
		if (base != null) {
			windowStyling.addStylesheet(base);
			String baseUri = windowStyling.getStylesheetUris().isEmpty() ? null
					: windowStyling.getStylesheetUris().getLast();
			if (baseUri != null)
				for (Stage stage : windowManager.getActiveWindows())
					if (stage.getScene() != null && !stage.getScene().getStylesheets().contains(baseUri))
						stage.getScene().getStylesheets().add(baseUri);
		}
		wrapMainRoot();
		Window.getWindows().addListener(windowListener);
		new ArrayList<>(Window.getWindows()).forEach(this::attach);
		mainStage.iconifiedProperty().addListener((ob, old, cur) -> canvas.setActive(!cur));
		mainStage.showingProperty().addListener((ob, old, cur) -> canvas.setActive(cur && !mainStage.isIconified()));
		Runnable schedule = this::scheduleApply;
		config.getPreset().addChangeListener((ob, old, cur) -> {
			if (cur != ThemeConfig.Preset.CUSTOM && cur != old) FxThreadUtil.run(() -> config.applyPreset(cur));
			schedule.run();
		});
		config.getBackground().addChangeListener((ob, old, cur) -> schedule.run());
		config.getColorA().addChangeListener((ob, old, cur) -> schedule.run());
		config.getColorB().addChangeListener((ob, old, cur) -> schedule.run());
		config.getAccent().addChangeListener((ob, old, cur) -> schedule.run());
		config.getGrayscaleIcons().addChangeListener((ob, old, cur) -> schedule.run());
		config.getGrayscaleSyntax().addChangeListener((ob, old, cur) -> schedule.run());
		config.getReduceMotion().addChangeListener((ob, old, cur) -> schedule.run());
		config.getGlass().addChangeListener((ob, old, cur) -> schedule.run());
		config.getGlassOpacity().addChangeListener((ob, old, cur) -> schedule.run());
		config.getRounded().addChangeListener((ob, old, cur) -> schedule.run());
		config.getCornerRadius().addChangeListener((ob, old, cur) -> schedule.run());
		config.getWindowCorners().addChangeListener((ob, old, cur) -> schedule.run());
		config.getAnimationSpeed().addChangeListener((ob, old, cur) -> schedule.run());
		config.getDensity().addChangeListener((ob, old, cur) -> schedule.run());
		config.getBlur().addChangeListener((ob, old, cur) -> schedule.run());
		apply();
	}

	public void scheduleApply() {
		FxThreadUtil.run(debounce::playFromStart);
	}

	private void wrapMainRoot() {
		Scene scene = mainStage.getScene();
		if (scene == null) return;
		Parent oldRoot = scene.getRoot();
		if (oldRoot.getStyleClass().contains("recafx-main")) return;
		stack = new StackPane();
		stack.getStyleClass().add("recafx-main");
		canvas.widthProperty().bind(stack.widthProperty());
		canvas.heightProperty().bind(stack.heightProperty());
		scene.setRoot(new Pane());
		stack.getChildren().addAll(canvas, oldRoot);
		scene.setRoot(stack);
	}

	public StackPane getMainStack() {return stack;}

	private void attach(Window window) {
		if (sceneListeners.containsKey(window)) return;
		if (window instanceof Stage stage) {
			recafx.theme.Branding.install(stage);
			NativeWindow.setRoundedCorners(stage, config.getRounded().getValue());
		}
		ChangeListener<Scene> listener = (ob, old, scene) -> { detachScene(old); attachScene(scene); };
		sceneListeners.put(window, listener);
		window.sceneProperty().addListener(listener);
		attachScene(window.getScene());
	}
	private void detach(Window window) {
		var listener = sceneListeners.remove(window);
		if (listener != null) window.sceneProperty().removeListener(listener);
		detachScene(window.getScene());
	}
	private void attachScene(Scene scene) {
		if (scene == null || iconScopes.containsKey(scene)) return;
		MonochromeIcons scope = new MonochromeIcons();
		scope.setGrayscaleSyntax(config.getGrayscaleSyntax().getValue() && config.getPreset().getValue() != ThemeConfig.Preset.RECAF_DEFAULT);
		scope.setEnabled(config.getGrayscaleIcons().getValue() && config.getPreset().getValue() != ThemeConfig.Preset.RECAF_DEFAULT);
		scope.watch(scene.getRoot());
		iconScopes.put(scene, scope);
		ChangeListener<Parent> listener = (ob, old, root) -> { scope.unwatch(old); scope.watch(root); };
		rootListeners.put(scene, listener);
		scene.rootProperty().addListener(listener);
		if (currentSheet != null && !scene.getStylesheets().contains(currentSheet)) scene.getStylesheets().add(currentSheet);
	}
	private void detachScene(Scene scene) {
		if (scene == null) return;
		if (currentSheet != null) scene.getStylesheets().remove(currentSheet);
		var scope = iconScopes.remove(scene);
		if (scope != null) scope.dispose();
		var listener = rootListeners.remove(scene);
		if (listener != null) scene.rootProperty().removeListener(listener);
	}

	/** Applies the current config to all windows. FX thread only. */
	public void apply() {
		try {
			ThemeConfig.Background background = config.getBackground().getValue();
			Color a = Fx.parseColor(config.getColorA().getValue(), Color.web("#1b1f2a"));
			Color b = Fx.parseColor(config.getColorB().getValue(), Color.web("#2c2c2c"));
			Color accent = Fx.parseColor(config.getAccent().getValue(), Color.web("#0a84ff"));
			boolean defaultTheme = config.getPreset().getValue() == ThemeConfig.Preset.RECAF_DEFAULT;
			canvas.setVisible(!defaultTheme);
			canvas.configure(background, a, b, accent, config.getReduceMotion().getValue() ? 0 : config.getAnimationSpeed().getValue() / 100.0,
					config.getDensity().getValue() / 100.0, config.getBlur().getValue());
			String css = defaultTheme ? "" : buildCss(background, a, b, accent);
			Path sheet = directories.getTempDirectory().resolve("recafx-theme-" + (++version) + ".css");
			Files.createDirectories(sheet.getParent());
			Files.writeString(sheet, css, StandardCharsets.UTF_8);
			String uri = sheet.toUri().toString();
			for (Scene scene : iconScopes.keySet()) {
				if (currentSheet != null) scene.getStylesheets().remove(currentSheet);
				scene.getStylesheets().add(uri);
			}
			if (sheetPath != null) Files.deleteIfExists(sheetPath);
			sheetPath = sheet;
			currentSheet = uri;
			iconScopes.values().forEach(scope -> scope.setEnabled(config.getGrayscaleIcons().getValue() && !defaultTheme));
			iconScopes.values().forEach(scope -> scope.setGrayscaleSyntax(config.getGrayscaleSyntax().getValue() && !defaultTheme));
			boolean round = config.getWindowCorners().getValue() && config.getRounded().getValue() && !defaultTheme;
			for (Stage stage : windowManager.getActiveWindows())
				NativeWindow.setRoundedCorners(stage, round || defaultTheme || !config.getWindowCorners().getValue());
		} catch (IOException ex) {
			logger.error("Failed writing theme stylesheet", ex);
		} catch (Throwable t) {
			logger.error("Failed applying theme", t);
		}
	}

	private String buildCss(ThemeConfig.Background background, Color a, Color b, Color accent) {
		boolean glass = config.getGlass().getValue();
		double alpha = background == ThemeConfig.Background.NONE ? 1.0 : glass ? config.getGlassOpacity().getValue() / 100.0 : 0.9;
		alpha = Math.max(0.15, Math.min(1.0, alpha));
		Color base = b;
		Color bgDefault = base.interpolate(Color.BLACK, 0.0);
		Color bgOverlay = base.interpolate(Color.BLACK, 0.25);
		Color bgInset = base.interpolate(Color.BLACK, 0.4);
		Color bgSubtle = base.interpolate(Color.WHITE, 0.04);
		Color border = base.interpolate(Color.WHITE, 0.22);
		Color borderMuted = base.interpolate(Color.WHITE, 0.12);
		Color borderSubtle = base.interpolate(Color.WHITE, 0.08);
		int radius = config.getRounded().getValue() ? Math.max(0, config.getCornerRadius().getValue()) : 0;
		StringBuilder css = new StringBuilder();
		css.append(".root {\n");
		css.append("  -fx-background-color: ").append(Fx.hex(bgInset)).append(";\n");
		css.append("  -color-fg-default: #e6e6e6; -color-fg-muted: #aaaaaa; -color-fg-subtle: #909090;\n");
		css.append("  -fx-focus-color: ").append(Fx.hex(accent)).append("; -fx-faint-focus-color: transparent;\n");
		css.append("  -color-bg-default: ").append(Fx.rgba(bgDefault, alpha)).append(";\n");
		css.append("  -color-bg-overlay: ").append(Fx.rgba(bgOverlay, Math.min(1, alpha + 0.08))).append(";\n");
		css.append("  -color-bg-subtle: ").append(Fx.rgba(bgSubtle, alpha)).append(";\n");
		css.append("  -color-bg-inset: ").append(Fx.rgba(bgInset, Math.min(1, alpha + 0.05))).append(";\n");
		css.append("  -color-border-default: ").append(Fx.hex(border)).append(";\n");
		css.append("  -color-border-muted: ").append(Fx.hex(borderMuted)).append(";\n");
		css.append("  -color-border-subtle: ").append(Fx.hex(borderSubtle)).append(";\n");
		css.append("  -color-accent-fg: ").append(Fx.hex(accent.brighter())).append(";\n");
		css.append("  -color-accent-emphasis: ").append(Fx.hex(accent)).append(";\n");
		css.append("  -color-accent-muted: ").append(Fx.rgba(accent, 0.4)).append(";\n");
		css.append("  -color-accent-subtle: ").append(Fx.rgba(accent, 0.15)).append(";\n");
		for (int i = 0; i <= 9; i++) {
			// 0 = lightest, 9 = darkest, 5 = the accent itself.
			Color c = i < 5 ? accent.interpolate(Color.WHITE, (5 - i) * 0.16) : accent.interpolate(Color.BLACK, (i - 5) * 0.16);
			css.append("  -color-accent-").append(i).append(": ").append(Fx.hex(c)).append(";\n");
		}
		css.append("  -color-chart-1: ").append(Fx.hex(accent)).append(";\n");
		css.append("}\n");
		css.append(".button:default, .button:default .ikonli-font-icon { -fx-text-fill: #101010; -fx-icon-color: #101010; }\n");
		if (config.getGrayscaleSyntax().getValue()) {
			css.append(".code-area .text { -fx-fill: #dedede; }\n");
			css.append(".code-area .keyword { -fx-fill: #ffffff; -fx-font-weight: bold; }\n");
			css.append(".code-area .comment { -fx-fill: #999999; -fx-font-style: italic; }\n");
			css.append(".code-area .string, .code-area .number { -fx-fill: #bbbbbb; }\n");
		}
		// Main window: let the canvas show through.
		css.append(".recafx-main { -fx-background-color: transparent; }\n");
		css.append(".recafx-main > .bg-inset { -fx-background-color: transparent !important; }\n");
		if (background != ThemeConfig.Background.NONE) {
			String panel = Fx.rgba(bgDefault, alpha);
			String overlay = Fx.rgba(bgOverlay, Math.min(1, alpha + 0.08));
			css.append(".container-leaf { -fx-background-color: ").append(panel).append("; }\n");
			css.append(".header-pane, .header-region, .header-region-wrapper { -fx-background-color: ").append(overlay).append("; }\n");
			css.append(".menu-bar { -fx-background-color: ").append(overlay).append("; }\n");
			css.append(".recafx-status-bar { -fx-background-color: ").append(overlay).append("; }\n");
			css.append(".tree-view, .list-view, .table-view, .text-area, .scroll-pane, .scroll-pane > .viewport, .tab-pane, .split-pane { -fx-background-color: ").append(panel).append("; }\n");
			css.append(".code-area { -fx-background-color: ").append(panel).append("; }\n");
			css.append(".code-area .lineno, .code-area .lineno .bg { -fx-background-color: ").append(overlay).append("; }\n");
			css.append(".tree-cell, .list-cell, .table-row-cell { -fx-background-color: transparent; }\n");
			css.append(".tree-cell:selected, .list-cell:selected, .table-row-cell:selected { -fx-background-color: -color-accent-subtle; }\n");
			css.append(".welcome-pane, .padded { -fx-background-color: transparent; }\n");
			// One translucent surface per dock region. Stacking the same opacity on
			// every ancestor makes the canvas effectively opaque (especially in editors).
			css.append(".recafx-main .container-branch { -fx-background-color: transparent; }\n");
			css.append(".recafx-main .container-leaf .tree-view, .recafx-main .container-leaf .list-view, .recafx-main .container-leaf .table-view, .recafx-main .container-leaf .code-area, .recafx-main .container-leaf .scroll-pane, .recafx-main .container-leaf .scroll-pane > .viewport, .recafx-main .container-leaf .tab-pane, .recafx-main .container-leaf .split-pane { -fx-background-color: transparent; }\n");
			css.append(".recafx-main .container-leaf .bg-inset, .recafx-main .container-leaf .bg-subtle, .recafx-main .container-leaf .background, .recafx-main .container-leaf .background-inset, .recafx-main .container-leaf .background-dark { -fx-background-color: transparent !important; }\n");
		}
		if (radius > 0) {
			String r = radius + "px";
			css.append(".container-leaf, .tree-view, .list-view, .table-view, .code-area, .text-area, .scroll-pane, .tab-pane, .header-pane, .menu-bar, .recafx-status-bar, .tool-bar, .titled-pane, .virtualized-scroll-pane {\n")
					.append("  -fx-background-radius: ").append(r).append("; -fx-border-radius: ").append(r).append(";\n}\n");
			css.append(".button, .toggle-button, .text-field, .combo-box, .combo-box-base, .choice-box, .menu-button, .spinner, .check-box > .box, .color-picker, .slider > .track, .tooltip, .context-menu, .popup {\n")
					.append("  -fx-background-radius: ").append(r).append("; -fx-border-radius: ").append(r).append(";\n}\n");
			css.append(".header { -fx-background-radius: ").append(r).append(' ').append(r).append(" 0 0; }\n");
			css.append(".header-region { -fx-background-radius: ").append(r).append(' ').append(r).append(" 0 0; }\n");
			css.append(".container-leaf { -fx-background-insets: 1; }\n");
		}
		return css.toString();
	}

	public void dispose() {
		debounce.stop();
		Window.getWindows().removeListener(windowListener);
		new ArrayList<>(sceneListeners.keySet()).forEach(this::detach);
		canvas.dispose();
	}
}
