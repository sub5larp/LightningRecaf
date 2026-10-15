package recafx.theme;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.image.ImageView;
import java.util.IdentityHashMap;
import java.util.Map;

/** Watches added/removed graphics so virtualized trees and newly opened tabs obey the icon setting. */
final class MonochromeIcons {
    private final Map<Parent, ListChangeListener<Node>> parents = new IdentityHashMap<>();
    private final Map<Node, Effect> originals = new IdentityHashMap<>();
    private final Map<Node, Effect> syntaxOriginals = new IdentityHashMap<>();
    private boolean enabled;
    private boolean grayscaleSyntax;
    void setGrayscaleSyntax(boolean enabled) {
        grayscaleSyntax = enabled;
        syntaxOriginals.forEach((node, original) -> node.setEffect(enabled ? grayscale(original) : original));
    }
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        for (Node node : originals.keySet()) apply(node);
    }
    void watch(Node node) {
        if (node instanceof javafx.scene.text.Text && !(node instanceof org.kordamp.ikonli.javafx.FontIcon) && inCodeArea(node)) {
            if (!syntaxOriginals.containsKey(node)) syntaxOriginals.put(node, node.getEffect());
            node.setEffect(grayscaleSyntax ? grayscale(syntaxOriginals.get(node)) : syntaxOriginals.get(node));
        }
        if (node instanceof ImageView || node instanceof org.kordamp.ikonli.javafx.FontIcon) {
            if (!originals.containsKey(node)) originals.put(node, node.getEffect());
            apply(node);
        }
        if (node instanceof Parent parent && !parents.containsKey(parent)) {
            ListChangeListener<Node> listener = change -> {
                while (change.next()) {
                    change.getRemoved().forEach(this::unwatch);
                    change.getAddedSubList().forEach(this::watch);
                }
            };
            parents.put(parent, listener);
            parent.getChildrenUnmodifiable().addListener(listener);
            parent.getChildrenUnmodifiable().forEach(this::watch);
        }
    }
    void unwatch(Node node) {
        if (syntaxOriginals.containsKey(node)) node.setEffect(syntaxOriginals.remove(node));
        if (originals.containsKey(node)) node.setEffect(originals.remove(node));
        if (node instanceof Parent parent) {
            var listener = parents.remove(parent);
            if (listener != null) parent.getChildrenUnmodifiable().removeListener(listener);
            parent.getChildrenUnmodifiable().forEach(this::unwatch);
        }
    }
    private void apply(Node node) {
        if (enabled) {
            ColorAdjust adjust = new ColorAdjust(0, -1, 0, 0);
            adjust.setInput(originals.get(node));
            node.setEffect(adjust);
        } else node.setEffect(originals.get(node));
    }
    private static boolean inCodeArea(Node node) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof org.fxmisc.richtext.CodeArea) return true;
        return false;
    }
    private static Effect grayscale(Effect input) {
        ColorAdjust adjust = new ColorAdjust(0, -1, 0, 0); adjust.setInput(input); return adjust;
    }
    void dispose() {
        for (var entry : parents.entrySet()) entry.getKey().getChildrenUnmodifiable().removeListener(entry.getValue());
        parents.clear();
        originals.forEach(Node::setEffect);
        originals.clear();
        syntaxOriginals.forEach(Node::setEffect);
        syntaxOriginals.clear();
    }
}
