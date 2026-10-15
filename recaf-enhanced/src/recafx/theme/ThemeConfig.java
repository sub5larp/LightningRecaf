package recafx.theme;

import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.observables.ObservableObject;
import software.coley.observables.ObservableString;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;

/**
 * Theme settings. Presets fill in the other values; {@link Preset#CUSTOM} keeps whatever the user set.
 */
public class ThemeConfig extends BasicConfigContainer {
	public static final String ID = "recafx-theme" + CONFIG_SUFFIX;

	public enum Preset {
		LIGHTNING("Lightning / graphite"),
		LIGHTNING_GLASS("Lightning / glass"),
		LIGHTNING_STARS("Lightning / stars"),
		RECAF_DEFAULT("Recaf default"),
		MIDNIGHT("Midnight"),
		OCEAN("Ocean gradient"),
		GLASS("Frosted glass"),
		AURORA("Aurora (animated)"),
		STARFIELD("Starfield (animated)"),
		NEBULA("Nebula (animated)"),
		WAVES("Waves (animated)"),
		MATRIX("Matrix rain (animated)"),
		EMBER("Ember gradient"),
		CUSTOM("Custom");

		public final String label;

		Preset(String label) {this.label = label;}
	}

	public enum Background {
		NONE("Solid"), GRADIENT("Gradient"), STARFIELD("Starfield"), AURORA("Aurora"), PARTICLES("Particles"),
		WAVES("Waves"), MATRIX("Matrix rain"), NEBULA("Nebula");

		public final String label;

		Background(String label) {this.label = label;}

		public boolean animated() {return this != NONE && this != GRADIENT;}
	}

	private final ObservableObject<Preset> preset = new ObservableObject<>(Preset.LIGHTNING);
	private final ObservableObject<Background> background = new ObservableObject<>(Background.NONE);
	private final ObservableString colorA = new ObservableString("#080808");
	private final ObservableString colorB = new ObservableString("#161616");
	private final ObservableString accent = new ObservableString("#eeeeee");
	private final ObservableBoolean grayscaleIcons = new ObservableBoolean(true);
	private final ObservableObject<CodePalette> codePalette = new ObservableObject<>(CodePalette.GRAYSCALE);
	private final ObservableBoolean reduceMotion = new ObservableBoolean(false);
	private final ObservableBoolean glass = new ObservableBoolean(false);
	private final ObservableInteger glassOpacity = new ObservableInteger(70);
	private final ObservableBoolean rounded = new ObservableBoolean(true);
	private final ObservableInteger cornerRadius = new ObservableInteger(8);
	private final ObservableBoolean windowCorners = new ObservableBoolean(true);
	private final ObservableInteger animationSpeed = new ObservableInteger(100);
	private final ObservableInteger density = new ObservableInteger(100);
	private final ObservableInteger blur = new ObservableInteger(0);

	public ThemeConfig() {
		super(ConfigGroups.EXTERNAL, ID);
		addValue(new BasicConfigValue<>("preset", Preset.class, preset));
		addValue(new BasicConfigValue<>("background", Background.class, background));
		addValue(new BasicConfigValue<>("color-a", String.class, colorA));
		addValue(new BasicConfigValue<>("color-b", String.class, colorB));
		addValue(new BasicConfigValue<>("accent", String.class, accent));
		addValue(new BasicConfigValue<>("grayscale-icons", boolean.class, grayscaleIcons));
		addValue(new BasicConfigValue<>("code-palette", CodePalette.class, codePalette));
		addValue(new BasicConfigValue<>("reduce-motion", boolean.class, reduceMotion));
		addValue(new BasicConfigValue<>("glass", boolean.class, glass));
		addValue(new BasicConfigValue<>("glass-opacity", int.class, glassOpacity));
		addValue(new BasicConfigValue<>("rounded", boolean.class, rounded));
		addValue(new BasicConfigValue<>("corner-radius", int.class, cornerRadius));
		addValue(new BasicConfigValue<>("window-corners", boolean.class, windowCorners));
		addValue(new BasicConfigValue<>("animation-speed", int.class, animationSpeed));
		addValue(new BasicConfigValue<>("density", int.class, density));
		addValue(new BasicConfigValue<>("blur", int.class, blur));
	}

	/** Applies a preset's values. */
	public void applyPreset(Preset p) {
		preset.setValue(p);
		switch (p) {
			case LIGHTNING -> set(Background.NONE, "#080808", "#161616", "#eeeeee", false, 85, true, 8, 0);
			case LIGHTNING_GLASS -> set(Background.GRADIENT, "#080808", "#252525", "#eeeeee", true, 72, true, 12, 6);
			case LIGHTNING_STARS -> set(Background.STARFIELD, "#050505", "#111111", "#dddddd", true, 72, true, 10, 0);
			case RECAF_DEFAULT -> set(Background.NONE, "#2c2c2c", "#2c2c2c", "#0a84ff", false, 70, false, 8, 0);
			case MIDNIGHT -> set(Background.NONE, "#121726", "#121726", "#4f8cff", false, 70, true, 8, 0);
			case OCEAN -> set(Background.GRADIENT, "#0b2a3f", "#071421", "#29b6f6", true, 72, true, 10, 0);
			case GLASS -> set(Background.GRADIENT, "#2a2d3a", "#141620", "#7c9cff", true, 55, true, 12, 6);
			case AURORA -> set(Background.AURORA, "#07101c", "#0f2a3a", "#4ad7a8", true, 62, true, 10, 0);
			case STARFIELD -> set(Background.STARFIELD, "#05070f", "#0b1026", "#8ab4ff", true, 60, true, 10, 0);
			case NEBULA -> set(Background.NEBULA, "#140b24", "#2b0f3d", "#d67cff", true, 60, true, 12, 4);
			case WAVES -> set(Background.WAVES, "#071c2b", "#0d3b4f", "#2ec4b6", true, 65, true, 10, 0);
			case MATRIX -> set(Background.MATRIX, "#020804", "#041207", "#39ff6a", true, 66, false, 2, 0);
			case EMBER -> set(Background.GRADIENT, "#2b0d0d", "#4a1c06", "#ff7a3d", false, 85, true, 10, 0);
			case CUSTOM -> {}
		}
	}

	private void set(Background bg, String a, String b, String acc, boolean gl, int opacity, boolean round, int radius, int blurPx) {
		background.setValue(bg);
		colorA.setValue(a);
		colorB.setValue(b);
		accent.setValue(acc);
		glass.setValue(gl);
		glassOpacity.setValue(opacity);
		rounded.setValue(round);
		cornerRadius.setValue(radius);
		blur.setValue(blurPx);
	}

	public ObservableObject<Preset> getPreset() {return preset;}

	public ObservableObject<Background> getBackground() {return background;}

	public ObservableString getColorA() {return colorA;}

	public ObservableString getColorB() {return colorB;}

	public ObservableString getAccent() {return accent;}
	public ObservableBoolean getGrayscaleIcons() {return grayscaleIcons;}
	public ObservableObject<CodePalette> getCodePalette() {return codePalette;}
	public ObservableBoolean getReduceMotion() {return reduceMotion;}

	public ObservableBoolean getGlass() {return glass;}

	public ObservableInteger getGlassOpacity() {return glassOpacity;}

	public ObservableBoolean getRounded() {return rounded;}

	public ObservableInteger getCornerRadius() {return cornerRadius;}

	public ObservableBoolean getWindowCorners() {return windowCorners;}

	public ObservableInteger getAnimationSpeed() {return animationSpeed;}

	public ObservableInteger getDensity() {return density;}

	public ObservableInteger getBlur() {return blur;}
}
