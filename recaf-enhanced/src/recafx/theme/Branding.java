package recafx.theme;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/** Product identity without removing upstream license or contributor attribution. */
public final class Branding {
    private Branding() {}
    public static void install(Stage main) {
        // Plugin resources have Recaf's custom URL scheme; JavaFX cannot reopen those URLs itself.
        try (var stream = Branding.class.getResourceAsStream("/recafx/logo.png")) {
            if (stream != null) main.getIcons().setAll(new Image(stream));
        } catch (java.io.IOException ignored) { }
        if (!main.titleProperty().isBound()) {
            main.setTitle(title(main.getTitle()));
            main.titleProperty().addListener((ob, old, value) -> {
                String branded = title(value);
                if (!branded.equals(value)) main.setTitle(branded);
            });
        }
        replaceWelcome(main.getScene().getRoot(), false);
    }
    private static String title(String value) {
        if (value == null || value.isBlank()) return "LightningRecaf";
        return value.replaceAll("(?<!Lightning)Recaf", "LightningRecaf");
    }
    private static void replaceWelcome(Node node, boolean welcome) {
        welcome |= node.getClass().getSimpleName().equals("WelcomePane");
        if (welcome && node instanceof Label label && !label.textProperty().isBound()
                && label.getText() != null && label.getText().startsWith("Recaf "))
            label.setText("LightningRecaf · " + label.getText().substring(6));
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) replaceWelcome(child, welcome);
    }
}
