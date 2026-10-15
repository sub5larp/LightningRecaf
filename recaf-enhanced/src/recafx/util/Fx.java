package recafx.util;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Cell;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Small JavaFX scene-graph helpers.
 */
public final class Fx {
	private Fx() {}

	/** Finds the first descendant (depth first) of the given type, or {@code null}. */
	public static <T> T findFirst(Node root, Class<T> type) {
		if (root == null) return null;
		if (type.isInstance(root)) return type.cast(root);
		if (root instanceof Parent parent)
			for (Node child : parent.getChildrenUnmodifiable()) {
				T found = findFirst(child, type);
				if (found != null) return found;
			}
		return null;
	}

	/** Collects all descendants of the given type. */
	public static <T> List<T> findAll(Node root, Class<T> type) {
		List<T> out = new ArrayList<>();
		collect(root, type, out);
		return out;
	}

	private static <T> void collect(Node node, Class<T> type, List<T> out) {
		if (node == null) return;
		if (type.isInstance(node)) out.add(type.cast(node));
		if (node instanceof Parent parent)
			for (Node child : parent.getChildrenUnmodifiable())
				collect(child, type, out);
	}

	/** Walks up the parent chain until a {@link Cell} is found. */
	public static Cell<?> enclosingCell(Node node) {
		Node current = node;
		while (current != null) {
			if (current instanceof Cell<?> cell) return cell;
			current = current.getParent();
		}
		return null;
	}

	public static String hex(Color c) {
		return String.format("#%02x%02x%02x",
				(int) Math.round(c.getRed() * 255),
				(int) Math.round(c.getGreen() * 255),
				(int) Math.round(c.getBlue() * 255));
	}

	public static String rgba(Color c, double alpha) {
		return String.format("rgba(%d,%d,%d,%.3f)",
				(int) Math.round(c.getRed() * 255),
				(int) Math.round(c.getGreen() * 255),
				(int) Math.round(c.getBlue() * 255), alpha);
	}

	public static Color parseColor(String text, Color fallback) {
		try {
			return Color.web(text);
		} catch (Exception ex) {
			return fallback;
		}
	}
}
