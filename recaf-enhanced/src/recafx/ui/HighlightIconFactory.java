package recafx.ui;

import jakarta.annotation.Nonnull;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Cell;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import recafx.EnhancedConfig;
import recafx.analysis.AnalysisService;
import recafx.analysis.NetworkAnalysis;
import recafx.analysis.ObfuscationAnalysis;
import recafx.util.Fx;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.cell.icon.ClassIconProviderFactory;
import software.coley.recaf.services.cell.icon.IconProvider;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

/**
 * Wraps the default class icon with colored badges and tints the owning tree/list cell's text:
 * red for network related classes, green for classes detected as obfuscated.
 */
public class HighlightIconFactory implements ClassIconProviderFactory {
	public static final String STYLE_NET = "-fx-text-fill: #ff6b63;";
	public static final String STYLE_OBF = "-fx-text-fill: #5ee070;";
	private static final Color NET_COLOR = Color.web("#ff4f45");
	private static final Color OBF_COLOR = Color.web("#3fd45a");
	private static final Object LISTENER_KEY = new Object();
	private final ClassIconProviderFactory delegate;
	private final AnalysisService analysis;
	private final EnhancedConfig config;
	private final recafx.deobf.ViewStateManager viewState;

	public HighlightIconFactory(ClassIconProviderFactory delegate, AnalysisService analysis, EnhancedConfig config,
	                            recafx.deobf.ViewStateManager viewState) {
		this.delegate = delegate;
		this.analysis = analysis;
		this.config = config;
		this.viewState = viewState;
	}

	@Nonnull
	@Override
	public IconProvider getJvmClassInfoIconProvider(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
	                                                @Nonnull JvmClassBundle bundle, @Nonnull JvmClassInfo info) {
		IconProvider base = delegate.getJvmClassInfoIconProvider(workspace, resource, bundle, info);
		return () -> {
			Node icon = base.makeIcon();
			if (icon == null) icon = new FontIconView(CarbonIcons.CODE);
			String name = info.getName();
			boolean net = config.getHighlightNetwork().getValue() && analysis.isNetworkClass(name);
			boolean obf = config.getHighlightObfuscated().getValue() && analysis.isObfuscatedClass(name);
			Badge badge = new Badge(icon, net, obf);
			String originalName = null;
			recafx.deobf.ViewStateManager.State state = viewState == null ? null : viewState.getState();
			if (state != null && state.getWorkspace() == workspace && state.hasNormalization()) {
				String o = state.originalNameOf(name);
				if (!o.equals(name)) originalName = o;
			}
			if (net || obf || originalName != null) {
				StringBuilder tip = new StringBuilder();
				if (originalName != null) tip.append("Original name: ").append(originalName.replace('/', '.'));
				if (net) {
					NetworkAnalysis.ClassFindings f = analysis.getNetwork(name);
					if (!tip.isEmpty()) tip.append('\n');
					tip.append("Network: ").append(f == null ? "" : f.summary());
				}
				if (obf) {
					ObfuscationAnalysis.ClassReport r = analysis.getObfuscation(name);
					if (!tip.isEmpty()) tip.append('\n');
					tip.append("Obfuscated: ").append(r == null ? "" : r.reasonSummary());
				}
				Tooltip.install(badge, new Tooltip(tip.toString()));
			}
			badge.style = net ? STYLE_NET : obf ? STYLE_OBF : "";
			badge.parentProperty().addListener((ob, old, parent) -> badge.applyToCell());
			return badge;
		};
	}

	/** Icon wrapper that also carries the text color to apply to the enclosing cell. */
	public static class Badge extends StackPane {
		String style = "";

		Badge(Node icon, boolean net, boolean obf) {
			getChildren().add(icon);
			setAlignment(Pos.CENTER);
			if (net) getChildren().add(dot(NET_COLOR, Pos.BOTTOM_RIGHT));
			if (obf) getChildren().add(dot(OBF_COLOR, Pos.TOP_RIGHT));
		}

		private static Circle dot(Color color, Pos pos) {
			Circle c = new Circle(3.2, color);
			c.setStroke(Color.rgb(20, 20, 20, 0.9));
			c.setStrokeWidth(0.8);
			StackPane.setAlignment(c, pos);
			c.setTranslateX(2);
			c.setTranslateY(pos == Pos.TOP_RIGHT ? -2 : 2);
			c.setMouseTransparent(true);
			return c;
		}

		void applyToCell() {
			Cell<?> cell = Fx.enclosingCell(this);
			if (cell == null) return;
			cell.setStyle(style);
			// When the cell is recycled for an item that does not come through this factory the style would
			// stick around, so clear it as soon as the graphic is replaced by something that is not a badge.
			if (!cell.getProperties().containsKey(LISTENER_KEY)) {
				ChangeListener<Node> listener = (ob, old, cur) -> {
					if (!(cur instanceof Badge b)) cell.setStyle("");
					else cell.setStyle(b.style);
				};
				cell.graphicProperty().addListener(listener);
				cell.getProperties().put(LISTENER_KEY, listener);
			}
		}
	}
}
