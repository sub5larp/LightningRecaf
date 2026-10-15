package recafx.theme;

import javafx.collections.ListChangeListener;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.Effect;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import software.coley.recaf.util.Icons;
import java.util.IdentityHashMap;
import java.util.Map;

/** Watches added/removed graphics so virtualized trees and newly opened tabs obey the icon setting. */
final class MonochromeIcons {
    private final Map<Parent, ListChangeListener<Node>> parents = new IdentityHashMap<>();
    private final Map<Node, Effect> originals = new IdentityHashMap<>();
    private final Map<ImageView, Image> originalImages = new IdentityHashMap<>();
    private final Map<ImageView, ChangeListener<Image>> imageListeners = new IdentityHashMap<>();
    private static Image blackJar;
    private boolean enabled;
    private boolean changingImage;
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        for (Node node : originals.keySet()) apply(node);
    }
    void watch(Node node) {
        if (node instanceof ImageView view && !imageListeners.containsKey(view)) {
            originalImages.put(view, view.getImage());
            ChangeListener<Image> listener = (ob, old, current) -> {
                if (changingImage) return;
                originalImages.put(view, current);
                apply(view);
            };
            imageListeners.put(view, listener);
            view.imageProperty().addListener(listener);
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
        if (node instanceof ImageView view) {
            var listener = imageListeners.remove(view);
            if (listener != null) {
                view.imageProperty().removeListener(listener);
                setImage(view, originalImages.remove(view));
            }
        }
        if (originals.containsKey(node)) node.setEffect(originals.remove(node));
        if (node instanceof Parent parent) {
            var listener = parents.remove(parent);
            if (listener != null) parent.getChildrenUnmodifiable().removeListener(listener);
            parent.getChildrenUnmodifiable().forEach(this::unwatch);
        }
    }
    private void apply(Node node) {
        if (node instanceof ImageView view) {
            Image source = originalImages.get(view);
            if (enabled && source == Icons.getImage(Icons.FILE_JAR) && !view.imageProperty().isBound()) {
                setImage(view, blackJar(source));
                node.setEffect(originals.get(node));
                return;
            }
            setImage(view, source);
        }
        if (enabled) {
            ColorAdjust adjust = new ColorAdjust(0, -1, 0, 0);
            adjust.setInput(originals.get(node));
            node.setEffect(adjust);
        } else node.setEffect(originals.get(node));
    }
    private void setImage(ImageView view, Image image) {
        if (view.imageProperty().isBound() || view.getImage() == image) return;
        changingImage = true;
        try { view.setImage(image); } finally { changingImage = false; }
    }

    private static Image blackJar(Image source) {
        if (blackJar != null) return blackJar;
        int width = (int) source.getWidth(), height = (int) source.getHeight();
        WritableImage result = new WritableImage(width, height);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int pixel = source.getPixelReader().getArgb(x, y);
            int r = (pixel >>> 16) & 255, g = (pixel >>> 8) & 255, b = pixel & 255;
            // Keep the white paper and transparency; ink and outline become solid black.
            int ink = Math.min(r, Math.min(g, b)) >= 250 ? 0xffffff : 0;
            result.getPixelWriter().setArgb(x, y, (pixel & 0xff000000) | ink);
        }
        return blackJar = result;
    }
    void dispose() {
        for (var entry : parents.entrySet()) entry.getKey().getChildrenUnmodifiable().removeListener(entry.getValue());
        parents.clear();
        imageListeners.forEach((view, listener) -> view.imageProperty().removeListener(listener));
        imageListeners.clear();
        originalImages.forEach(this::setImage);
        originalImages.clear();
        originals.forEach(Node::setEffect);
        originals.clear();
    }
}
