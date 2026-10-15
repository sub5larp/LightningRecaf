package recafx.theme;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.StringConverter;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import recafx.util.Fx;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.observables.ObservableString;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.FxThreadUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Live theme editor: preset tiles on top, fine-grained controls below. Every change applies immediately.
 */
public class ThemeChooserWindow extends RecafStage {
	private final ThemeConfig config;
	private final List<VBox> tiles = new ArrayList<>();
	private boolean syncing;

	public ThemeChooserWindow(ThemeConfig config) {
		this.config = config;
		setTitle("Theme chooser");
		BorderPane root = new BorderPane();
		root.setPadding(new Insets(12));

		Label title = new Label("Presets");
		title.getStyleClass().add("title-3");
		FlowPane presets = new FlowPane(10, 10);
		presets.setPadding(new Insets(6, 0, 10, 0));
		for (ThemeConfig.Preset preset : ThemeConfig.Preset.values()) {
			if (preset == ThemeConfig.Preset.CUSTOM) continue;
			presets.getChildren().add(tile(preset));
		}

		GridPane grid = new GridPane();
		grid.setHgap(12);
		grid.setVgap(8);
		grid.setPadding(new Insets(6, 0, 0, 0));
		int row = 0;
		ComboBox<ThemeConfig.Background> background = new ComboBox<>();
		background.getItems().addAll(ThemeConfig.Background.values());
		background.setConverter(new StringConverter<>() {
			@Override
			public String toString(ThemeConfig.Background b) {return b == null ? "" : b.label;}

			@Override
			public ThemeConfig.Background fromString(String s) {return null;}
		});
		background.setValue(config.getBackground().getValue());
		background.valueProperty().addListener((ob, old, cur) -> edit(() -> config.getBackground().setValue(cur)));
		config.getBackground().addChangeListener((ob, old, cur) -> FxThreadUtil.run(() -> {
			if (background.getValue() != cur) { syncing = true; background.setValue(cur); syncing = false; }
		}));
		grid.addRow(row++, new Label("Background"), background);
		grid.addRow(row++, new Label("Color A (top-left)"), colorPicker(config.getColorA()));
		grid.addRow(row++, new Label("Color B (base)"), colorPicker(config.getColorB()));
		grid.addRow(row++, new Label("Accent"), colorPicker(config.getAccent()));
		grid.addRow(row++, new Label("Grayscale icons (keep red/green indicators)"), checkBox(config.getGrayscaleIcons()));
		grid.addRow(row++, new Label("Grayscale code colors"), checkBox(config.getGrayscaleSyntax()));
		grid.addRow(row++, new Label("Reduce motion / freeze animated background"), checkBox(config.getReduceMotion()));
		grid.addRow(row++, new Label("Frosted glass panels"), checkBox(config.getGlass()));
		grid.addRow(row++, new Label("Panel opacity"), slider(config.getGlassOpacity(), 15, 100, "%"));
		grid.addRow(row++, new Label("Rounded edges"), checkBox(config.getRounded()));
		grid.addRow(row++, new Label("Corner radius"), slider(config.getCornerRadius(), 0, 24, "px"));
		grid.addRow(row++, new Label("Round the window itself (Windows 11)"), checkBox(config.getWindowCorners()));
		grid.addRow(row++, new Label("Animation speed"), slider(config.getAnimationSpeed(), 10, 300, "%"));
		grid.addRow(row++, new Label("Animation density"), slider(config.getDensity(), 20, 250, "%"));
		grid.addRow(row++, new Label("Background blur"), slider(config.getBlur(), 0, 30, "px"));

		Button reset = new Button("Reset to LightningRecaf", new FontIconView(CarbonIcons.RESET));
		reset.setOnAction(e -> { config.applyPreset(ThemeConfig.Preset.LIGHTNING); config.getGrayscaleIcons().setValue(true); config.getGrayscaleSyntax().setValue(true); });
		Button close = new Button("Close", new FontIconView(CarbonIcons.CHECKMARK));
		close.setOnAction(e -> close());
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox buttons = new HBox(8, reset, spacer, close);
		buttons.setPadding(new Insets(12, 0, 0, 0));

		VBox content = new VBox(6, title, presets, new Separator(), new Label("Fine tuning (changes apply instantly and are saved with your config)"), grid, buttons);
		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("dark-scroll-pane");
		root.setCenter(scroll);
		setScene(new RecafScene(root, 760, 640));
		setWidth(760);
		setHeight(640);
		setOnShown(event -> FxThreadUtil.run(() -> { scroll.setVvalue(0); root.requestFocus(); }));
		config.getPreset().addChangeListener((ob, old, cur) -> FxThreadUtil.run(this::syncTiles));
		syncTiles();
	}

	private VBox tile(ThemeConfig.Preset preset) {
		Rectangle preview = new Rectangle(150, 70);
		preview.getStyleClass().add("recafx-theme-preview");
		preview.setArcWidth(10);
		preview.setArcHeight(10);
		ThemeConfig sample = new ThemeConfig();
		sample.applyPreset(preset);
		Color a = Fx.parseColor(sample.getColorA().getValue(), Color.GRAY);
		Color b = Fx.parseColor(sample.getColorB().getValue(), Color.DARKGRAY);
		Color accent = Fx.parseColor(sample.getAccent().getValue(), Color.DODGERBLUE);
		preview.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE, new Stop(0, a), new Stop(0.7, b), new Stop(1, accent.deriveColor(0, 1, 1, 0.6))));
		Label name = new Label(preset.label);
		name.setStyle("-fx-font-weight: bold;");
		Label sub = new Label(sample.getBackground().getValue().label + (sample.getGlass().getValue() ? " + glass" : "") + (sample.getRounded().getValue() ? " + rounded" : ""));
		sub.getStyleClass().add("recafx-progress");
		VBox box = new VBox(4, preview, name, sub);
		box.setAlignment(Pos.TOP_LEFT);
		box.getStyleClass().add("recafx-theme-tile");
		box.setUserData(preset);
		box.setOnMouseClicked(e -> config.applyPreset(preset));
		tiles.add(box);
		return box;
	}

	private void syncTiles() {
		ThemeConfig.Preset current = config.getPreset().getValue();
		for (VBox tile : tiles) {
			boolean selected = tile.getUserData() == current;
			if (selected) {
				if (!tile.getStyleClass().contains("selected")) tile.getStyleClass().add("selected");
			} else {
				tile.getStyleClass().remove("selected");
			}
		}
	}

	private void edit(Runnable change) {
		if (syncing) return;
		change.run();
		if (config.getPreset().getValue() != ThemeConfig.Preset.CUSTOM) config.getPreset().setValue(ThemeConfig.Preset.CUSTOM);
	}

	private ColorPicker colorPicker(ObservableString value) {
		ColorPicker picker = new ColorPicker(Fx.parseColor(value.getValue(), Color.GRAY));
		picker.valueProperty().addListener((ob, old, cur) -> edit(() -> value.setValue(Fx.hex(cur))));
		value.addChangeListener((ob, old, cur) -> FxThreadUtil.run(() -> {
			Color c = Fx.parseColor(cur, picker.getValue());
			if (!Fx.hex(c).equals(Fx.hex(picker.getValue()))) {
				syncing = true;
				picker.setValue(c);
				syncing = false;
			}
		}));
		return picker;
	}

	private CheckBox checkBox(ObservableBoolean value) {
		CheckBox box = new CheckBox();
		box.setSelected(value.getValue());
		box.selectedProperty().addListener((ob, old, cur) -> edit(() -> value.setValue(cur)));
		value.addChangeListener((ob, old, cur) -> FxThreadUtil.run(() -> {
			if (box.isSelected() != cur) {
				syncing = true;
				box.setSelected(cur);
				syncing = false;
			}
		}));
		return box;
	}

	private HBox slider(ObservableInteger value, int min, int max, String unit) {
		Slider slider = new Slider(min, max, value.getValue());
		slider.setPrefWidth(260);
		Label label = new Label(value.getValue() + unit);
		label.setMinWidth(48);
		slider.valueProperty().addListener((ob, old, cur) -> {
			int v = (int) Math.round(cur.doubleValue());
			label.setText(v + unit);
			if (v != value.getValue()) edit(() -> value.setValue(v));
		});
		value.addChangeListener((ob, old, cur) -> FxThreadUtil.run(() -> {
			if ((int) Math.round(slider.getValue()) != cur) {
				syncing = true;
				slider.setValue(cur);
				syncing = false;
			}
		}));
		HBox box = new HBox(8, slider, label);
		box.setAlignment(Pos.CENTER_LEFT);
		return box;
	}
}
