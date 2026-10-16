package recafx;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import recafx.analysis.AnalysisService;
import recafx.deobf.AutoDeobfuscator;
import recafx.deobf.StringBuilderFoldingTransformer;
import recafx.deobf.UnreferencedPrivateMemberRemovingTransformer;
import recafx.deobf.ViewStateManager;
import recafx.theme.ThemeChooserWindow;
import recafx.theme.ThemeConfig;
import recafx.theme.ThemeManager;
import recafx.ui.EditorFlagger;
import recafx.ui.EnhancedMenu;
import recafx.ui.ExplorerTabs;
import recafx.ui.HighlightIconFactory;
import recafx.ui.NetworkReportPane;
import recafx.ui.StatusBar;
import recafx.util.ConfigRestore;
import recafx.util.Fx;
import software.coley.bentofx.dockable.Dockable;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.config.ConfigContainer;
import software.coley.recaf.config.ConfigValue;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.cell.context.ContextMenuProviderService;
import software.coley.recaf.services.cell.icon.IconProviderService;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.config.ConfigComponentManager;
import software.coley.recaf.services.config.ConfigManager;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.json.GsonProvider;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.gen.MappingGenerator;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.NavigationManager;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.window.WindowManager;
import software.coley.recaf.services.window.WindowStyling;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.config.RecentFilesConfig;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.ui.menubar.MainMenu;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.PathLoadingManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@PluginInformation(id = "recaf-enhanced", name = "LightningRecaf", version = recafx.theme.Branding.VERSION, author = "LightningRecaf contributors",
		description = "Automatic deobfuscation with notes and original/deobfuscated/normalized views, theme chooser, " +
				"multi-tab explorer, network/obfuscation highlighting and quality-of-life additions.")
public class RecafEnhancedPlugin implements Plugin, EnhancedMenu.Host {
	private static final Logger logger = Logging.get(RecafEnhancedPlugin.class);

	// Core services (safe to resolve before the UI exists)
	private final WorkspaceManager workspaceManager;
	private final TransformationManager transformationManager;
	private final InheritanceGraphService inheritanceGraphServiceForTransforms;
	private final ConfigManager configManager;
	private final RecafDirectoriesConfig directories;
	private final GsonProvider gsonProvider;
	private final software.coley.recaf.services.mapping.MappingListeners mappingListeners;
	// UI services (resolved lazily on the FX thread)
	private final Instance<WindowManager> windowManagerProvider;
	private final Instance<WindowStyling> windowStylingProvider;
	private final Instance<DockingManager> dockingManagerProvider;
	private final Instance<CellConfigurationService> cellConfigurationProvider;
	private final Instance<IconProviderService> iconProviderServiceProvider;
	private final Instance<ContextMenuProviderService> contextMenuProvider;
	private final Instance<NavigationManager> navigationManagerProvider;
	private final Instance<Actions> actionsProvider;
	private final Instance<MainMenu> mainMenuProvider;
	private final Instance<PathLoadingManager> pathLoadingProvider;
	private final Instance<RecentFilesConfig> recentFilesProvider;
	private final Instance<ConfigComponentManager> configComponentProvider;

	private final EnhancedConfig config = new EnhancedConfig();
	private final ThemeConfig themeConfig = new ThemeConfig();
	private final AnalysisService analysis = new AnalysisService();
	private final ViewStateManager viewState;
	private final AutoDeobfuscator deobfuscator;
	private ThemeManager themeManager;
	private ExplorerTabs explorerTabs;
	private EditorFlagger editorFlagger;
	private StatusBar statusBar;
	private Dockable networkDockable;
	private ThemeChooserWindow themeWindow;
	private volatile boolean uiInstalled;
	private volatile boolean disabled;
	private software.coley.recaf.services.workspace.WorkspaceOpenListener openListener;
	private software.coley.recaf.services.workspace.WorkspaceCloseListener closeListener;
	private software.coley.recaf.services.mapping.MappingApplicationListener mappingListener;

	@Inject
	public RecafEnhancedPlugin(WorkspaceManager workspaceManager,
	                           TransformationManager transformationManager,
	                           TransformationApplierService transformationApplierService,
	                           MappingApplierService mappingApplierService,
	                           MappingGenerator mappingGenerator,
	                           InheritanceGraphService inheritanceGraphService,
	                           CommentManager commentManager,
	                           ConfigManager configManager,
	                           RecafDirectoriesConfig directories,
	                           GsonProvider gsonProvider,
	                           software.coley.recaf.services.mapping.MappingListeners mappingListeners,
	                           Instance<WindowManager> windowManagerProvider,
	                           Instance<WindowStyling> windowStylingProvider,
	                           Instance<DockingManager> dockingManagerProvider,
	                           Instance<CellConfigurationService> cellConfigurationProvider,
	                           Instance<IconProviderService> iconProviderServiceProvider,
	                           Instance<ContextMenuProviderService> contextMenuProvider,
	                           Instance<NavigationManager> navigationManagerProvider,
	                           Instance<Actions> actionsProvider,
	                           Instance<MainMenu> mainMenuProvider,
	                           Instance<PathLoadingManager> pathLoadingProvider,
	                           Instance<RecentFilesConfig> recentFilesProvider,
	                           Instance<ConfigComponentManager> configComponentProvider) {
		this.workspaceManager = workspaceManager;
		this.transformationManager = transformationManager;
		this.inheritanceGraphServiceForTransforms = inheritanceGraphService;
		this.configManager = configManager;
		this.directories = directories;
		this.gsonProvider = gsonProvider;
		this.mappingListeners = mappingListeners;
		this.windowManagerProvider = windowManagerProvider;
		this.windowStylingProvider = windowStylingProvider;
		this.dockingManagerProvider = dockingManagerProvider;
		this.cellConfigurationProvider = cellConfigurationProvider;
		this.iconProviderServiceProvider = iconProviderServiceProvider;
		this.contextMenuProvider = contextMenuProvider;
		this.navigationManagerProvider = navigationManagerProvider;
		this.actionsProvider = actionsProvider;
		this.mainMenuProvider = mainMenuProvider;
		this.pathLoadingProvider = pathLoadingProvider;
		this.recentFilesProvider = recentFilesProvider;
		this.configComponentProvider = configComponentProvider;
		this.viewState = new ViewStateManager(mappingApplierService);
		this.deobfuscator = new AutoDeobfuscator(transformationManager, transformationApplierService, mappingApplierService,
				mappingGenerator, inheritanceGraphService, commentManager, analysis, viewState, config);
	}

	// ------------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------------

	@Override
	public void onEnable() {
		addTranslations();
		registerConfig(config);
		registerConfig(themeConfig);
		transformationManager.registerJvmClassTransformer(UnreferencedPrivateMemberRemovingTransformer.class,
				() -> new UnreferencedPrivateMemberRemovingTransformer(analysis::isObfuscatedClass));
		transformationManager.registerJvmClassTransformer(StringBuilderFoldingTransformer.class, StringBuilderFoldingTransformer::new);
		transformationManager.registerJvmClassTransformer(recafx.deobf.PureCallFoldingTransformer.class,
				() -> new recafx.deobf.PureCallFoldingTransformer(inheritanceGraphServiceForTransforms));
		transformationManager.registerJvmClassTransformer(recafx.deobf.ExpressionFoldingTransformer.class,
				() -> new recafx.deobf.ExpressionFoldingTransformer(inheritanceGraphServiceForTransforms));
		recafx.deobf.FamilyRecovery.register(transformationManager, inheritanceGraphServiceForTransforms);
		transformationManager.registerJvmClassTransformer(recafx.deobf.GuardedStaticValueCollector.class,
				() -> new recafx.deobf.GuardedStaticValueCollector(inheritanceGraphServiceForTransforms));
		transformationManager.registerJvmClassTransformer(recafx.deobf.GuardedStaticValueInliner.class,
				() -> new recafx.deobf.GuardedStaticValueInliner(inheritanceGraphServiceForTransforms));
		transformationManager.registerJvmClassTransformer(recafx.deobf.ported.SeededRandomFoldingTransformer.class,
				recafx.deobf.ported.SeededRandomFoldingTransformer::new);
		transformationManager.registerJvmClassTransformer(recafx.deobf.MethodNormalizingTransformer.class,
				recafx.deobf.MethodNormalizingTransformer::new);
		transformationManager.registerJvmClassTransformer(recafx.deobf.ported.NumericEncodingFoldingTransformer.class,
				recafx.deobf.ported.NumericEncodingFoldingTransformer::new);
		transformationManager.registerJvmClassTransformer(recafx.deobf.JunkClassRemovingTransformer.class,
				() -> new recafx.deobf.JunkClassRemovingTransformer(analysis::isObfuscatedClass));
		mappingListener = new software.coley.recaf.services.mapping.MappingApplicationListener() {
			@Override
			public void onPreApply(@jakarta.annotation.Nonnull Workspace workspace, @jakarta.annotation.Nonnull software.coley.recaf.services.mapping.MappingResults results) {}

			@Override
			public void onPostApply(@jakarta.annotation.Nonnull Workspace workspace, @jakarta.annotation.Nonnull software.coley.recaf.services.mapping.MappingResults results) {
				if (analysis.getWorkspace() == workspace) analysis.onClassesRenamed(results.getMappedClasses());
			}
		};
		mappingListeners.addMappingApplicationListener(mappingListener);
		openListener = workspace -> {
			deobfuscator.onWorkspaceOpened(workspace);
		};
		workspaceManager.addWorkspaceOpenListener(openListener);
		closeListener = workspace -> {
			deobfuscator.onWorkspaceClosed();
			analysis.clear();
			viewState.clear();
			if (explorerTabs != null) explorerTabs.onWorkspaceClosed(workspace);
			FxThreadUtil.run(() -> {
				if (networkDockable != null) {
					try {
						networkDockable.inContainer(leaf -> leaf.closeDockable(networkDockable));
					} catch (Throwable ignored) {}
					networkDockable = null;
				}
			});
		};
		workspaceManager.addWorkspaceCloseListener(closeListener);
		// Plugin activation can happen after a file is already open. Catch up with that workspace;
		// repeated open notifications are coalesced by the recovery service.
		if (workspaceManager.hasCurrentWorkspace()) deobfuscator.onWorkspaceOpened(workspaceManager.getCurrent());

		// The UI does not exist yet (plugins are enabled before JavaFX starts), so wait for the main window.
		waitForMainWindow(0);
		logger.info("LightningRecaf enabled");
	}

	private void waitForMainWindow(int attempt) {
		if (disabled) return;
		if (attempt > 1200) {
			logger.warn("LightningRecaf: main window never appeared, UI features not installed");
			return;
		}
		FxThreadUtil.delayedRun(attempt == 0 ? 500 : 250, () -> {
			try {
				Stage main = windowManagerProvider.get().getMainWindow();
				if (main != null && main.getScene() != null && main.isShowing()) {
					installUi();
					return;
				}
			} catch (Throwable t) {
				if (attempt % 40 == 0) logger.debug("Waiting for main window: {}", t.toString());
			}
			waitForMainWindow(attempt + 1);
		});
	}

	@Override
	public void onDisable() {
		disabled = true;
		if (openListener != null) workspaceManager.removeWorkspaceOpenListener(openListener);
		if (closeListener != null) workspaceManager.removeWorkspaceCloseListener(closeListener);
		if (mappingListener != null) mappingListeners.removeMappingApplicationListener(mappingListener);
		deobfuscator.onWorkspaceClosed();
		deobfuscator.getExecutor().shutdownNow();
		analysis.close();
		FxThreadUtil.run(() -> {
			if (themeManager != null) themeManager.dispose();
			if (explorerTabs != null) explorerTabs.dispose();
			if (editorFlagger != null) {
				navigationManagerProvider.get().removeNavigableAddListener(editorFlagger);
				navigationManagerProvider.get().removeNavigableRemoveListener(editorFlagger);
				editorFlagger.dispose();
			}
		});
		logger.info("LightningRecaf disabled");
	}

	private void registerConfig(ConfigContainer container) {
		try {
			configManager.registerContainer(container);
			ConfigRestore.restore(directories, gsonProvider, container);
		} catch (IllegalStateException ex) {
			logger.warn("Config container already registered: {}", container.getId());
		}
	}

	private void installUi() {
		if (uiInstalled) return;
		uiInstalled = true;
		try {
			WindowManager windowManager = windowManagerProvider.get();
			Stage main = windowManager.getMainWindow();
			recafx.theme.Branding.install(main);

			// Highlighting in trees and editors
			IconProviderService icons = iconProviderServiceProvider.get();
			icons.setClassIconProviderOverride(new HighlightIconFactory(icons.getClassIconDefault(), analysis, config, viewState));
			editorFlagger = new EditorFlagger(analysis, config);
			navigationManagerProvider.get().addNavigableAddListener(editorFlagger);
			navigationManagerProvider.get().addNavigableRemoveListener(editorFlagger);
			analysis.addListener(new recafx.util.FxCoalescer(() -> {
				if (disabled) return;
				editorFlagger.refreshAll();
				refreshTrees();
			}));

			// Explorer tabs
			explorerTabs = new ExplorerTabs(dockingManagerProvider.get(), cellConfigurationProvider.get(),
					contextMenuProvider.get(), actionsProvider.get());
			explorerTabs.install();

			// Theme
			themeManager = new ThemeManager(themeConfig, windowManager, windowStylingProvider.get(), directories);
			themeManager.install(main);
			registerColorPickers();

			// Status bar + menu
			statusBar = new StatusBar(workspaceManager, analysis, viewState, deobfuscator, this::openNetworkReport, this::openThemeChooser);
			if (themeManager.getMainStack() != null) {
				Node content = themeManager.getMainStack().getChildren().getLast();
				if (content instanceof BorderPane wrapper) wrapper.setBottom(statusBar);
			}
			config.getStatusBar().addChangeListener((ob, old, cur) -> FxThreadUtil.run(() -> {
				statusBar.setVisible(cur);
				statusBar.setManaged(cur);
			}));
			statusBar.setVisible(config.getStatusBar().getValue());
			statusBar.setManaged(config.getStatusBar().getValue());
			MainMenu menu = mainMenuProvider.get();
			menu.getMenus().add(new EnhancedMenu(this, workspaceManager, viewState, config));
			logger.info("LightningRecaf UI installed");
		} catch (Throwable t) {
			logger.error("LightningRecaf UI installation failed", t);
		}
	}

	/** Forces tree cells to re-render so badges / colors reflect fresh analysis results. */
	private void refreshTrees() {
		try {
			Stage main = windowManagerProvider.get().getMainWindow();
			if (main == null || main.getScene() == null) return;
			for (javafx.scene.control.TreeView<?> tree : Fx.findAll(main.getScene().getRoot(), javafx.scene.control.TreeView.class))
				tree.refresh();
		} catch (Throwable ignored) {}
	}

	private void registerColorPickers() {
		try {
			ConfigComponentManager components = configComponentProvider.get();
			for (String id : new String[]{"color-a", "color-b", "accent"}) {
				components.register(themeConfig, id, false, (ConfigContainer container, ConfigValue<String> value) -> {
					ColorPicker picker = new ColorPicker(Fx.parseColor(value.getValue(), Color.GRAY));
					picker.valueProperty().addListener((ob, old, cur) -> {
						value.setValue(Fx.hex(cur));
						if (themeConfig.getPreset().getValue() != ThemeConfig.Preset.CUSTOM)
							themeConfig.getPreset().setValue(ThemeConfig.Preset.CUSTOM);
					});
					return picker;
				});
			}
		} catch (Throwable t) {
			logger.debug("Color picker registration failed: {}", t.toString());
		}
	}

	private void addTranslations() {
		try {
			Map<String, Map<String, String>> all = Lang.getTranslations();
			for (Map<String, String> map : all.values()) {
				map.putIfAbsent("external", "Plugins");
				String e = "external." + EnhancedConfig.ID;
				map.put(e, "LightningRecaf");
				map.put(e + ".auto-deobfuscate", "Auto-deobfuscate when a workspace opens");
				map.put(e + ".extended-recovery", "Extended recovery (model-dependent; inspect output)");
				map.put(e + ".only-when-detected", "Only when obfuscation is detected");
				map.put(e + ".normalize-names", "Normalize unreadable names after deobfuscation");
				map.put(e + ".rename-minified", "Also rename single-letter names in obfuscated classes");
				map.put(e + ".remove-unreferenced-private", "Remove unreferenced private static helpers");
				map.put(e + ".add-notes", "Add a note (comment) to obfuscated / transformed classes");
				map.put(e + ".max-passes", "Maximum transformer passes");
				map.put(e + ".max-auto-classes", "Skip automatic run above this many classes");
				map.put(e + ".highlight-network", "Highlight network related classes in red");
				map.put(e + ".highlight-obfuscated", "Highlight obfuscated classes in green");
				map.put(e + ".editor-flags", "Flag network lines in the code editor");
				map.put(e + ".status-bar", "Show the status bar");
				String t = "external." + ThemeConfig.ID;
				map.put(t, "LightningRecaf: Theme");
				map.put(t + ".preset", "Preset");
				map.put(t + ".background", "Background style");
				map.put(t + ".color-a", "Background color A");
				map.put(t + ".color-b", "Background color B / panel base");
				map.put(t + ".accent", "Accent color");
				map.put(t + ".grayscale-icons", "Grayscale icons");
				map.put(t + ".code-palette", "Code colors");
				map.put(t + ".reduce-motion", "Reduce motion");
				map.put(t + ".glass", "Frosted glass panels");
				map.put(t + ".glass-opacity", "Panel opacity (%)");
				map.put(t + ".rounded", "Rounded edges");
				map.put(t + ".corner-radius", "Corner radius (px)");
				map.put(t + ".window-corners", "Round the window (Windows 11)");
				map.put(t + ".animation-speed", "Animation speed (%)");
				map.put(t + ".density", "Animation density (%)");
				map.put(t + ".blur", "Background blur (px)");
				map.put("deobf." + UnreferencedPrivateMemberRemovingTransformer.IDENTIFIER, "Unreferenced private member removal");
				map.put("deobf." + StringBuilderFoldingTransformer.IDENTIFIER, "StringBuilder constant folding");
				map.put("deobf." + recafx.deobf.JunkClassRemovingTransformer.IDENTIFIER, "Junk (phantom) class removal");
				map.put("deobf.recafx", "LightningRecaf");
				map.put("deobf.recafx.cleanup", "Cleanup");
				map.put("deobf.recafx.peephole", "Peephole");
			}
		} catch (Throwable t) {
			logger.debug("Translations could not be added: {}", t.toString());
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Menu host
	// ------------------------------------------------------------------------------------------------

	@Override
	public void openThemeChooser() {
		if (themeWindow == null) {
			themeWindow = new ThemeChooserWindow(themeConfig);
			windowManagerProvider.get().registerAnonymous(themeWindow);
		}
		themeWindow.show();
		themeWindow.requestFocus();
	}

	@Override
	public void deobfuscateNow() {
		Workspace ws = workspaceManager.getCurrent();
		if (ws != null) deobfuscator.runNow(ws);
	}

	@Override
	public void setView(ViewStateManager.View view) {
		deobfuscator.getExecutor().execute(() -> viewState.setView(view));
	}

	@Override
	public void openNetworkReport() {
		Workspace ws = workspaceManager.getCurrent();
		if (ws == null) return;
		DockingManager docking = dockingManagerProvider.get();
		if (networkDockable != null) {
			try {
				networkDockable.inContainer(leaf -> leaf.selectDockable(networkDockable));
				return;
			} catch (Throwable ignored) {
				networkDockable = null;
			}
		}
		NetworkReportPane pane = new NetworkReportPane(analysis, workspaceManager, actionsProvider.get());
		Dockable dockable = docking.newDockable("Network report", CarbonIcons.NETWORK_3, pane);
		dockable.setClosable(true);
		dockable.addCloseListener((path, d) -> { pane.dispose(); networkDockable = null; });
		networkDockable = dockable;
		docking.getPrimaryDockingContainer().addDockable(dockable);
		docking.getPrimaryDockingContainer().selectDockable(dockable);
	}

	@Override
	public void openRecentPicker() {
		RecentFilesConfig recent = recentFilesProvider.get();
		List<RecentFilesConfig.WorkspaceModel> models = new ArrayList<>(recent.getRecentWorkspaces().getValue());
		RecafStage stage = new RecafStage();
		stage.setTitle("Open recent workspace");
		ListView<RecentFilesConfig.WorkspaceModel> list = new ListView<>();
		list.getItems().addAll(models);
		list.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
			@Override
			protected void updateItem(RecentFilesConfig.WorkspaceModel item, boolean empty) {
				super.updateItem(item, empty);
				setText(empty || item == null ? null : item.primary().path() + (item.libraries().isEmpty() ? "" : "  (+" + item.libraries().size() + " libraries)"));
				setGraphic(empty || item == null ? null : new software.coley.recaf.ui.control.FontIconView(CarbonIcons.ARCHIVE));
			}
		});
		Runnable open = () -> {
			RecentFilesConfig.WorkspaceModel model = list.getSelectionModel().getSelectedItem();
			if (model == null) return;
			stage.close();
			List<Path> libs = new ArrayList<>();
			for (RecentFilesConfig.ResourceModel lib : model.libraries()) libs.add(Path.of(lib.path()));
			Path primary = Path.of(model.primary().path());
			if (!Files.exists(primary)) {
				Alert alert = new Alert(Alert.AlertType.ERROR, "File no longer exists:\n" + primary, ButtonType.OK);
				alert.show();
				return;
			}
			pathLoadingProvider.get().asyncNewWorkspace(primary, libs, t -> logger.error("Failed opening {}", primary, t));
		};
		list.setOnMouseClicked(e -> {
			if (e.getClickCount() == 2) open.run();
		});
		list.setOnKeyPressed(e -> {
			if (e.getCode() == KeyCode.ENTER) open.run();
			else if (e.getCode() == KeyCode.ESCAPE) stage.close();
		});
		BorderPane root = new BorderPane(list);
		stage.setScene(new RecafScene(root, 640, 360));
		windowManagerProvider.get().registerAnonymous(stage);
		stage.show();
		list.requestFocus();
		if (!models.isEmpty()) list.getSelectionModel().select(0);
	}

	@Override
	public void openContainingFolder() {
		Workspace ws = workspaceManager.getCurrent();
		if (ws == null || !(ws.getPrimaryResource() instanceof WorkspaceFileResource fr)) return;
		String path = fr.getFileInfo().getName();
		try {
			Path p = Path.of(path).toAbsolutePath();
			if (System.getProperty("os.name", "").toLowerCase().contains("win"))
				new ProcessBuilder("explorer.exe", "/select,", p.toString()).start();
			else
				new ProcessBuilder("xdg-open", p.getParent().toString()).start();
		} catch (Exception ex) {
			logger.warn("Could not open folder for {}", path, ex);
		}
	}

	@Override
	public void closeExplorerTabs() {
		if (explorerTabs != null) explorerTabs.closeAll();
	}

	@Override
	public void showAbout() {
		Alert alert = new Alert(Alert.AlertType.INFORMATION);
		alert.setTitle("LightningRecaf");
		alert.setHeaderText("LightningRecaf 2 · Java reverse engineering workbench");
		alert.setContentText("""
				- Automatic deobfuscation on open (audited pipeline + ZKM/DashO patterns + custom recovery),
				  with a note on each affected class and Original / Deobfuscated / Normalized views (status bar, Lightning > View).
				- Red = network related classes and lines (HTTP, sockets, remote code loading, URLs, webhooks).
				  Green = classes detected as obfuscated. Hover icons / gutter marks for details.
				- Right-click a package or folder > "Open in new explorer tab" (up to 10 tabs).
				- Lightning > Theme chooser: gradients, glass, animated backgrounds, rounded edges.
				- Ctrl+Shift+O quick recent, Ctrl+Shift+N network report, Ctrl+Shift+D deobfuscate, Ctrl+Alt+1/2/3 views.
				""");
		alert.show();
	}
	@Override public void openRecoveryReport() {
		var window=new recafx.ui.RecoveryReportWindow(deobfuscator,analysis,viewState);
		windowManagerProvider.get().registerAnonymous(window);
		window.show();
	}
}
