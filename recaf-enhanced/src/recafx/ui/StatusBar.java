package recafx.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import recafx.analysis.AnalysisService;
import recafx.analysis.ObfuscationAnalysis;
import recafx.deobf.AutoDeobfuscator;
import recafx.deobf.ViewStateManager;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

/**
 * Slim bar at the bottom of the main window: workspace facts, obfuscation verdict, network count,
 * deobfuscation progress and the view toggles.
 */
public class StatusBar extends HBox {
	private final WorkspaceManager workspaceManager;
	private final AnalysisService analysis;
	private final ViewStateManager viewState;
	private final AutoDeobfuscator deobfuscator;
	private final Label workspaceLabel = new Label();
	private final Label obfLabel = new Label();
	private final Button netButton = new Button();
	private final Label progressLabel = new Label();
	private final ProgressIndicator spinner = new ProgressIndicator();
	private final ToggleButton deobfToggle = new ToggleButton("Deobfuscated");
	private final ToggleButton normToggle = new ToggleButton("Normalized names");
	private final Button runButton = new Button();
	private final Button cancelButton = new Button();
	private boolean updatingToggles;
	private boolean viewChangePending;
	private final Runnable refreshLater = new recafx.util.FxCoalescer(this::refresh);

	public StatusBar(WorkspaceManager workspaceManager, AnalysisService analysis, ViewStateManager viewState,
	                 AutoDeobfuscator deobfuscator, Runnable openNetworkReport, Runnable openTheme) {
		this.workspaceManager = workspaceManager;
		this.analysis = analysis;
		this.viewState = viewState;
		this.deobfuscator = deobfuscator;
		getStyleClass().add("recafx-status-bar");
		setAlignment(Pos.CENTER_LEFT);
		setSpacing(8);
		setPadding(new Insets(3, 8, 3, 8));

		workspaceLabel.setGraphic(new FontIconView(CarbonIcons.ARCHIVE));
		obfLabel.setGraphic(new FontIconView(CarbonIcons.SECURITY));
		netButton.setGraphic(new FontIconView(CarbonIcons.NETWORK_3));
		netButton.getStyleClass().addAll("flat", "small", "recafx-net-button");
		netButton.setTooltip(new Tooltip("Open the network report"));
		netButton.setOnAction(e -> openNetworkReport.run());
		spinner.setPrefSize(14, 14);
		spinner.setMinSize(14, 14);
		spinner.setVisible(false);
		spinner.setManaged(false);
		progressLabel.getStyleClass().add("recafx-progress");
		// Keep view controls legible at the default window width. Detailed status
		// remains available on hover rather than squeezing the buttons to icons.
		workspaceLabel.setMaxWidth(150);
		obfLabel.setMaxWidth(130);
		netButton.setMaxWidth(115);
		progressLabel.setMinWidth(0);
		progressLabel.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(progressLabel, Priority.ALWAYS);

		deobfToggle.setGraphic(new FontIconView(CarbonIcons.MAGIC_WAND));
		deobfToggle.getStyleClass().addAll("small");
		deobfToggle.setTooltip(new Tooltip("Show the deobfuscated classes (off = original bytecode as loaded)"));
		normToggle.setGraphic(new FontIconView(CarbonIcons.TAG));
		normToggle.getStyleClass().addAll("small");
		normToggle.setTooltip(new Tooltip("Rename unreadable / minified members to normalized names"));
		deobfToggle.setMinWidth(Region.USE_PREF_SIZE);
		normToggle.setMinWidth(Region.USE_PREF_SIZE);
		deobfToggle.setOnAction(e -> onToggle());
		normToggle.setOnAction(e -> onToggle());

		runButton.setGraphic(new FontIconView(CarbonIcons.PLAY));
		runButton.getStyleClass().addAll("flat", "small");
		runButton.setTooltip(new Tooltip("Run / re-run automatic deobfuscation now"));
		runButton.setOnAction(e -> {
			Workspace ws = workspaceManager.getCurrent();
			if (ws != null) deobfuscator.runNow(ws);
		});
		cancelButton.setGraphic(new FontIconView(CarbonIcons.STOP));
		cancelButton.getStyleClass().addAll("flat", "small");
		cancelButton.setTooltip(new Tooltip("Cancel the running deobfuscation"));
		cancelButton.setOnAction(e -> deobfuscator.cancel());
		Button themeButton = new Button();
		themeButton.setGraphic(new FontIconView(CarbonIcons.COLOR_PALETTE));
		themeButton.getStyleClass().addAll("flat", "small");
		themeButton.setTooltip(new Tooltip("Theme chooser"));
		themeButton.setOnAction(e -> openTheme.run());

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		getChildren().addAll(workspaceLabel, sep(), obfLabel, sep(), netButton, sep(), spinner, progressLabel, spacer,
				deobfToggle, normToggle, runButton, cancelButton, sep(), themeButton);

		analysis.addListener(refreshLater);
		viewState.addListener(refreshLater);
		deobfuscator.addListener(p -> refreshLater.run());
		refresh();
	}

	private static Separator sep() {
		Separator s = new Separator(javafx.geometry.Orientation.VERTICAL);
		s.setPadding(new Insets(0, 2, 0, 2));
		return s;
	}

	private void onToggle() {
		if (updatingToggles || viewChangePending || viewState.isSwitching()) return;
		ViewStateManager.View target = !deobfToggle.isSelected() ? ViewStateManager.View.ORIGINAL
				: normToggle.isSelected() ? ViewStateManager.View.NORMALIZED : ViewStateManager.View.DEOBFUSCATED;
		ViewStateManager.State state = viewState.getState();
		if (state == null) return;
		if (target == ViewStateManager.View.NORMALIZED && !state.hasNormalization())
			target = ViewStateManager.View.DEOBFUSCATED;
		if (target != ViewStateManager.View.ORIGINAL && !state.hasDeobfuscation()) {
			refresh();
			return;
		}
		ViewStateManager.View finalTarget = target;
		viewChangePending = true;
		refresh();
		deobfuscator.getExecutor().execute(() -> {
			try {
				if (viewState.getState() == state) viewState.setView(finalTarget);
			} finally {
				FxThreadUtil.run(() -> { viewChangePending = false; refresh(); });
			}
		});
	}

	private void setToggleEnabled(boolean enabled) {
		deobfToggle.setDisable(!enabled);
		normToggle.setDisable(!enabled);
	}

	public void refresh() {
		Workspace ws = workspaceManager.hasCurrentWorkspace() ? workspaceManager.getCurrent() : null;
		if (ws == null) {
			workspaceLabel.setText("No workspace");
			obfLabel.setText("");
			netButton.setText("");
			progressLabel.setText("");
			spinner.setVisible(false);
			spinner.setManaged(false);
			updatingToggles = true;
			deobfToggle.setSelected(false);
			normToggle.setSelected(false);
			updatingToggles = false;
			setToggleEnabled(false);
			runButton.setDisable(true);
			cancelButton.setDisable(true);
			return;
		}
		WorkspaceResource primary = ws.getPrimaryResource();
		String name = primary instanceof WorkspaceFileResource fr ? simpleFileName(fr.getFileInfo()) : "workspace";
		int classes = (int) primary.jvmAllClassBundleStreamRecursive().mapToLong(b -> b.size()).sum();
		int files = primary.getFileBundle().size();
		workspaceLabel.setText(name + "  -  " + classes + " classes, " + files + " files");
		workspaceLabel.setTooltip(new Tooltip(workspaceLabel.getText()));

		ObfuscationAnalysis.Report report = analysis.getReport();
		if (!analysis.isComplete() || analysis.getWorkspace() != ws) {
			obfLabel.setText("Analyzing...");
		} else if (report.obfuscator() == null) {
			obfLabel.setText("No obfuscation indicators");
		} else {
			obfLabel.setText(report.obfuscator() + " (score " + report.confidence() + "/100), " + report.obfuscatedCount() + " classes");
		}
		obfLabel.setTooltip(new Tooltip(report.evidence().isEmpty() ? "No obfuscation evidence" : String.join("\n", report.evidence())));
		int net = analysis.networkClassCount();
		netButton.setText(net == 0 ? "No network indicators" : net + " network class" + (net == 1 ? "" : "es"));
		netButton.setStyle(net == 0 ? "" : HighlightIconFactory.STYLE_NET);

		AutoDeobfuscator.Progress progress = deobfuscator.getProgress();
		boolean switching = viewChangePending || viewState.isSwitching();
		boolean running = progress.running() || switching;
		spinner.setVisible(running);
		spinner.setManaged(running);
		progressLabel.setText(switching ? "Switching view..." : progress.message());
		progressLabel.setTooltip(new Tooltip(progress.message()));
		runButton.setDisable(running);
		cancelButton.setDisable(!progress.running());

		ViewStateManager.State state = viewState.getState();
		updatingToggles = true;
		if (state == null || state.getWorkspace() != ws) {
			deobfToggle.setSelected(false);
			normToggle.setSelected(false);
			setToggleEnabled(false);
		} else {
			ViewStateManager.View view = state.getView();
			deobfToggle.setSelected(view != ViewStateManager.View.ORIGINAL);
			normToggle.setSelected(view == ViewStateManager.View.NORMALIZED);
			deobfToggle.setDisable(running || !state.hasDeobfuscation());
			normToggle.setDisable(running || !state.hasNormalization() || view == ViewStateManager.View.ORIGINAL);
		}
		updatingToggles = false;
	}

	private static String simpleFileName(FileInfo info) {
		String n = info.getName();
		int i = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
		return i < 0 ? n : n.substring(i + 1);
	}
}
