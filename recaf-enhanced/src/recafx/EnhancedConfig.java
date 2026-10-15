package recafx;

import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;

/**
 * Settings for the LightningRecaf plugin (shown in the config window under "Plugins").
 */
public class EnhancedConfig extends BasicConfigContainer {
	public static final String ID = "recafx-enhanced" + CONFIG_SUFFIX;
	private final ObservableBoolean autoDeobfuscate = new ObservableBoolean(true);
	private final ObservableBoolean onlyWhenDetected = new ObservableBoolean(false);
	private final ObservableBoolean extendedRecovery = new ObservableBoolean(false);
	private final ObservableBoolean normalizeNames = new ObservableBoolean(true);
	private final ObservableBoolean renameMinified = new ObservableBoolean(true);
	private final ObservableBoolean removeUnreferencedPrivate = new ObservableBoolean(false);
	private final ObservableBoolean addNotes = new ObservableBoolean(true);
	private final ObservableInteger maxPasses = new ObservableInteger(8);
	private final ObservableInteger maxAutoClasses = new ObservableInteger(15000);
	private final ObservableBoolean highlightNetwork = new ObservableBoolean(true);
	private final ObservableBoolean highlightObfuscated = new ObservableBoolean(true);
	private final ObservableBoolean editorFlags = new ObservableBoolean(true);
	private final ObservableBoolean statusBar = new ObservableBoolean(true);

	public EnhancedConfig() {
		super(ConfigGroups.EXTERNAL, ID);
		addValue(new BasicConfigValue<>("auto-deobfuscate", boolean.class, autoDeobfuscate));
		addValue(new BasicConfigValue<>("only-when-detected", boolean.class, onlyWhenDetected));
		addValue(new BasicConfigValue<>("extended-recovery", boolean.class, extendedRecovery));
		addValue(new BasicConfigValue<>("normalize-names", boolean.class, normalizeNames));
		addValue(new BasicConfigValue<>("rename-minified", boolean.class, renameMinified));
		addValue(new BasicConfigValue<>("remove-unreferenced-private", boolean.class, removeUnreferencedPrivate));
		addValue(new BasicConfigValue<>("add-notes", boolean.class, addNotes));
		addValue(new BasicConfigValue<>("max-passes", int.class, maxPasses));
		addValue(new BasicConfigValue<>("max-auto-classes", int.class, maxAutoClasses));
		addValue(new BasicConfigValue<>("highlight-network", boolean.class, highlightNetwork));
		addValue(new BasicConfigValue<>("highlight-obfuscated", boolean.class, highlightObfuscated));
		addValue(new BasicConfigValue<>("editor-flags", boolean.class, editorFlags));
		addValue(new BasicConfigValue<>("status-bar", boolean.class, statusBar));
	}

	public ObservableBoolean getAutoDeobfuscate() {return autoDeobfuscate;}

	public ObservableBoolean getOnlyWhenDetected() {return onlyWhenDetected;}
	public ObservableBoolean getExtendedRecovery() {return extendedRecovery;}

	public ObservableBoolean getNormalizeNames() {return normalizeNames;}

	public ObservableBoolean getRenameMinified() {return renameMinified;}

	public ObservableBoolean getRemoveUnreferencedPrivate() {return removeUnreferencedPrivate;}

	public ObservableBoolean getAddNotes() {return addNotes;}

	public ObservableInteger getMaxPasses() {return maxPasses;}

	public ObservableInteger getMaxAutoClasses() {return maxAutoClasses;}

	public ObservableBoolean getHighlightNetwork() {return highlightNetwork;}

	public ObservableBoolean getHighlightObfuscated() {return highlightObfuscated;}

	public ObservableBoolean getEditorFlags() {return editorFlags;}

	public ObservableBoolean getStatusBar() {return statusBar;}
}
