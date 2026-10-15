package recafx.ui;

import jakarta.annotation.Nonnull;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.fxmisc.richtext.CodeArea;
import recafx.EnhancedConfig;
import recafx.analysis.AnalysisService;
import recafx.analysis.NetworkAnalysis;
import recafx.analysis.ObfuscationAnalysis;
import recafx.util.Fx;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.navigation.NavigableAddListener;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.linegraphics.AbstractLineGraphicFactory;
import software.coley.recaf.ui.control.richtext.linegraphics.LineContainer;
import software.coley.recaf.ui.control.richtext.linegraphics.LineGraphicFactory;
import software.coley.recaf.ui.pane.editing.ClassPane;
import software.coley.recaf.util.FxThreadUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flags network related lines (red) and the declaration of obfuscated classes (green) in decompiler editors.
 */
public class EditorFlagger implements NavigableAddListener, software.coley.recaf.services.navigation.NavigableRemoveListener {
	public static final String STYLE_NET_LINE = "recafx-net-line";
	public static final String STYLE_OBF_LINE = "recafx-obf-line";
	private static final Pattern DECLARATION = Pattern.compile("\\b(class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");
	private final AnalysisService analysis;
	private final EnhancedConfig config;
	private final Map<Editor, LineFlags> installed = Collections.synchronizedMap(new WeakHashMap<>());
	private final java.util.Set<ClassPane> closed = Collections.newSetFromMap(new WeakHashMap<>());
	private boolean disposed;

	public EditorFlagger(AnalysisService analysis, EnhancedConfig config) {
		this.analysis = analysis;
		this.config = config;
		config.getEditorFlags().addChangeListener((ob, old, cur) -> refreshAll());
		config.getHighlightNetwork().addChangeListener((ob, old, cur) -> refreshAll());
		config.getHighlightObfuscated().addChangeListener((ob, old, cur) -> refreshAll());
	}

	@Override
	public void onAdd(@Nonnull Navigable navigable) {
		if (navigable instanceof ClassPane pane) { closed.remove(pane); attachLater(pane, 0); }
	}
	@Override public void onRemove(@Nonnull Navigable navigable) {
		if (!(navigable instanceof ClassPane pane)) return;
		closed.add(pane);
		synchronized(installed) {
			installed.entrySet().removeIf(entry -> {
				if(entry.getValue().pane != pane)return false;
				entry.getValue().dispose();return true;
			});
		}
	}
	public void dispose() {
		disposed = true;
		synchronized(installed) { installed.values().forEach(LineFlags::dispose); installed.clear(); }
	}

	private void attachLater(ClassPane pane, int attempt) {
		if (attempt > 40) return;
		FxThreadUtil.delayedRun(attempt == 0 ? 50 : 200, () -> {
			if (disposed || closed.contains(pane)) return;
			Editor editor = Fx.findFirst(pane.getDisplay(), Editor.class);
			if (editor == null) {
				attachLater(pane, attempt + 1);
				return;
			}
			if (installed.containsKey(editor)) return;
			LineFlags flags = new LineFlags(pane, editor);
			installed.put(editor, flags);
			editor.getRootLineGraphicFactory().addLineGraphicFactory(flags);
			editor.textProperty().addListener(flags.textListener);
			flags.recompute(editor.getText());
		});
	}

	/** Re-evaluates every open editor (after analysis results change). */
	public void refreshAll() {
		FxThreadUtil.run(() -> {
			List<LineFlags> copy;
			synchronized (installed) {
				copy = new ArrayList<>(installed.values());
			}
			for (LineFlags flags : copy) flags.recompute(flags.editor.getText());
		});
	}

	private class LineFlags extends AbstractLineGraphicFactory {
		private final ClassPane pane;
		private final Editor editor;
		private Map<Integer, NetworkAnalysis.LineHit> netLines = Map.of();
		private int obfLine = -1;
		private String obfReason = "";
		private String previousText;
		private ObfuscationAnalysis.ClassReport previousReport;
		private int previousOptions = -1;
		private final javafx.beans.value.ChangeListener<String> textListener = (ob, old, cur) -> recompute(cur);

		LineFlags(ClassPane pane, Editor editor) {
			super(LineGraphicFactory.P_LINE_PROBLEMS + 5);
			this.pane = pane;
			this.editor = editor;
		}

		@Override
		public void install(@Nonnull Editor editor) {}

		@Override
		public void uninstall(@Nonnull Editor editor) {}
		void dispose() {
			editor.textProperty().removeListener(textListener);
			editor.getRootLineGraphicFactory().removeLineGraphicFactory(this);
			netLines = Map.of(); obfLine = -1; applyParagraphStyles();
		}

		void recompute(String text) {
			ClassPathNode currentPath = pane.getPath();
			ObfuscationAnalysis.ClassReport currentReport = currentPath == null ? null : analysis.getObfuscation(currentPath.getValue().getName());
			int options = (config.getEditorFlags().getValue() ? 1 : 0) | (config.getHighlightNetwork().getValue() ? 2 : 0)
					| (config.getHighlightObfuscated().getValue() ? 4 : 0);
			if (java.util.Objects.equals(previousText, text) && previousReport == currentReport && previousOptions == options) return;
			previousText = text;
			previousReport = currentReport;
			previousOptions = options;
			Map<Integer, NetworkAnalysis.LineHit> hits = new HashMap<>();
			int declarationLine = -1;
			String reason = "";
			if (text != null && !text.isEmpty()) {
				if (config.getEditorFlags().getValue() && config.getHighlightNetwork().getValue())
					for (NetworkAnalysis.LineHit hit : NetworkAnalysis.matchSource(text))
						hits.putIfAbsent(hit.line(), hit);
				ClassPathNode path = pane.getPath();
				if (path != null && config.getEditorFlags().getValue() && config.getHighlightObfuscated().getValue()) {
					String name = path.getValue().getName();
					ObfuscationAnalysis.ClassReport report = analysis.getObfuscation(name);
					if (report != null && report.obfuscated()) {
						String simple = name.substring(name.lastIndexOf('/') + 1);
						simple = simple.substring(simple.lastIndexOf('$') + 1);
						String[] lines = text.split("\n", -1);
						for (int i = 0; i < lines.length; i++) {
							Matcher m = DECLARATION.matcher(lines[i]);
							if (m.find() && (m.group(2).equals(simple) || declarationLine < 0 && !lines[i].trim().startsWith("*"))) {
								declarationLine = i;
								if (m.group(2).equals(simple)) break;
							}
						}
						reason = report.reasonSummary();
					}
				}
			}
			netLines = hits;
			obfLine = declarationLine;
			obfReason = reason;
			applyParagraphStyles();
			editor.redrawParagraphGraphics();
		}

		private void applyParagraphStyles() {
			CodeArea area = editor.getCodeArea();
			int paragraphs = area.getParagraphs().size();
			for (int i = 0; i < paragraphs; i++) {
				try {
					// Preserve debugger, search and other plugins' paragraph markers.
					List<String> wanted = new ArrayList<>(area.getParagraph(i).getParagraphStyle());
					wanted.remove(STYLE_NET_LINE);
					wanted.remove(STYLE_OBF_LINE);
					if (netLines.containsKey(i)) wanted.add(STYLE_NET_LINE);
					else if (i == obfLine) wanted.add(STYLE_OBF_LINE);
					if (!wanted.equals(new ArrayList<>(area.getParagraph(i).getParagraphStyle())))
						area.setParagraphStyle(i, wanted);
				} catch (Throwable ignored) {
					// Document changed under us, the next text change triggers a recompute.
				}
			}
		}

		@Override
		public void apply(@Nonnull LineContainer container, int paragraph) {
			NetworkAnalysis.LineHit hit = netLines.get(paragraph);
			boolean obf = paragraph == obfLine;
			if (hit == null && !obf) return;
			Color color = hit != null ? Color.web("#ff4f45") : Color.web("#3fd45a");
			Rectangle shape = new Rectangle();
			shape.widthProperty().bind(container.widthProperty().map(Number::intValue));
			shape.heightProperty().bind(container.heightProperty().map(Number::intValue));
			shape.setFill(color);
			shape.setOpacity(0.35);
			shape.setCursor(Cursor.HAND);
			Tooltip tooltip = new Tooltip(hit != null ? hit.kind().label + ": " + hit.detail() : "Obfuscated class: " + obfReason);
			tooltip.getStyleClass().add("mono-text");
			Tooltip.install(shape, tooltip);
			container.addTopLayer(shape);
		}
	}
}
