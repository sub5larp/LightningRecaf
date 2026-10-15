package recafx.ui;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import recafx.EnhancedConfig;
import recafx.deobf.ViewStateManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.FxThreadUtil;

/**
 * The "Enhanced" entry in the main menu bar.
 */
public class EnhancedMenu extends Menu {
	public interface Host {
		void openThemeChooser();

		void deobfuscateNow();

		void setView(ViewStateManager.View view);

		void openNetworkReport();

		void openRecentPicker();

		void openContainingFolder();

		void closeExplorerTabs();

		void showAbout();
		void openRecoveryReport();
	}

	private final RadioMenuItem viewOriginal = new RadioMenuItem("Original");
	private final RadioMenuItem viewDeobf = new RadioMenuItem("Deobfuscated");
	private final RadioMenuItem viewNormalized = new RadioMenuItem("Deobfuscated + normalized names");
	private boolean updating;

	public EnhancedMenu(Host host, WorkspaceManager workspaceManager, ViewStateManager viewState, EnhancedConfig config) {
		super("Lightning");
		setGraphic(new FontIconView(CarbonIcons.MAGIC_WAND));

		MenuItem theme = item("Theme chooser...", CarbonIcons.COLOR_PALETTE, host::openThemeChooser);
		theme.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
		MenuItem deobf = item("Deobfuscate now", CarbonIcons.PLAY, host::deobfuscateNow);
		deobf.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));

		Menu view = new Menu("View", new FontIconView(CarbonIcons.VIEW));
		ToggleGroup group = new ToggleGroup();
		for (RadioMenuItem item : new RadioMenuItem[]{viewOriginal, viewDeobf, viewNormalized}) {
			item.setToggleGroup(group);
			view.getItems().add(item);
		}
		viewOriginal.setOnAction(e -> {if (!updating) host.setView(ViewStateManager.View.ORIGINAL);});
		viewDeobf.setOnAction(e -> {if (!updating) host.setView(ViewStateManager.View.DEOBFUSCATED);});
		viewNormalized.setOnAction(e -> {if (!updating) host.setView(ViewStateManager.View.NORMALIZED);});
		viewOriginal.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
		viewDeobf.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
		viewNormalized.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));

		MenuItem network = item("Network report", CarbonIcons.NETWORK_3, host::openNetworkReport);
		network.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
		MenuItem recent = item("Quick open recent workspace...", CarbonIcons.TIME, host::openRecentPicker);
		recent.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN));
		MenuItem folder = item("Open containing folder", CarbonIcons.FOLDER, host::openContainingFolder);
		MenuItem closeTabs = item("Close all explorer tabs", CarbonIcons.CLOSE, host::closeExplorerTabs);

		Menu options = new Menu("Options", new FontIconView(CarbonIcons.SETTINGS));
		options.getItems().addAll(
				check("Auto-deobfuscate on open", config.getAutoDeobfuscate()),
				check("Extended recovery (model-dependent; review output)", config.getExtendedRecovery()),
				check("Only when obfuscation is detected", config.getOnlyWhenDetected()),
				check("Normalize names", config.getNormalizeNames()),
				check("Also rename minified single-letter names", config.getRenameMinified()),
				check("Add notes to classes", config.getAddNotes()),
				new SeparatorMenuItem(),
				check("Highlight network classes (red)", config.getHighlightNetwork()),
				check("Highlight obfuscated classes (green)", config.getHighlightObfuscated()),
				check("Flag lines in the code editor", config.getEditorFlags()));

		MenuItem about = item("About LightningRecaf", CarbonIcons.INFORMATION, host::showAbout);
		MenuItem report = item("Recovery evidence & coverage...", CarbonIcons.REPORT, host::openRecoveryReport);
		getItems().addAll(deobf, view, report, network, new SeparatorMenuItem(), theme, new SeparatorMenuItem(),
				recent, folder, closeTabs, new SeparatorMenuItem(), options, about);

		Runnable sync = () -> FxThreadUtil.run(() -> {
			ViewStateManager.State state = viewState.getState();
			boolean hasWs = workspaceManager.hasCurrentWorkspace();
			deobf.setDisable(!hasWs);
			network.setDisable(!hasWs);
			folder.setDisable(!hasWs);
			updating = true;
			if (state == null) {
				viewOriginal.setSelected(true);
				view.setDisable(true);
			} else {
				view.setDisable(false);
				switch (state.getView()) {
					case ORIGINAL -> viewOriginal.setSelected(true);
					case DEOBFUSCATED -> viewDeobf.setSelected(true);
					case NORMALIZED -> viewNormalized.setSelected(true);
				}
				viewDeobf.setDisable(!state.hasDeobfuscation());
				viewNormalized.setDisable(!state.hasNormalization());
			}
			updating = false;
		});
		viewState.addListener(sync);
		workspaceManager.addWorkspaceOpenListener(ws -> sync.run());
		workspaceManager.addWorkspaceCloseListener(ws -> sync.run());
		sync.run();
	}

	private static MenuItem item(String text, org.kordamp.ikonli.Ikon icon, Runnable action) {
		MenuItem item = new MenuItem(text, new FontIconView(icon));
		item.setOnAction(e -> action.run());
		return item;
	}

	private static CheckMenuItem check(String text, software.coley.observables.ObservableBoolean value) {
		CheckMenuItem item = new CheckMenuItem(text);
		item.setSelected(value.getValue());
		item.selectedProperty().addListener((ob, old, cur) -> value.setValue(cur));
		value.addChangeListener((ob, old, cur) -> FxThreadUtil.run(() -> item.setSelected(cur)));
		return item;
	}
}
