package recafx.theme;

/** Syntax colors are independent of the window theme and icon colors. */
public enum CodePalette {
    GRAYSCALE("Grayscale", "#dedede", "#ffffff", "#bcbcbc", "#eeeeee", "#cccccc", "#929292"),
    PURPLE("Purple", "#e8e1f0", "#c6a0ff", "#e4baff", "#f0d5ff", "#ad91e8", "#a69ab5"),
    BLUE("Blue", "#e0eaf4", "#77baff", "#b2dfff", "#d0eeff", "#95aaff", "#91a6bd"),
    GREEN("Green", "#e0eee5", "#7bdea0", "#b6e3a0", "#dbf3c4", "#81c9b0", "#94ae9e"),
    RECAF_DEFAULT("Recaf original", null, null, null, null, null, null);

    public final String label;
    private final String text, keyword, string, constant, annotation, comment;

    CodePalette(String label, String text, String keyword, String string, String constant, String annotation, String comment) {
        this.label = label;
        this.text = text;
        this.keyword = keyword;
        this.string = string;
        this.constant = constant;
        this.annotation = annotation;
        this.comment = comment;
    }

    public String stylesheet() {
        if (this == RECAF_DEFAULT) return "";
        StringBuilder css = new StringBuilder();
        rule(css, text, "text");
        rule(css, keyword, "keyword", "tag-name", "key", "section");
        rule(css, string, "string");
        rule(css, constant, "constant", "number");
        rule(css, annotation, "annotation", "tag-param", "comment-javadoc-name");
        rule(css, comment, "comment", "comment-line", "comment-multi", "comment-javadoc");
        rule(css, string, "comment-javadoc-link");
        css.append(".root .code-area .keyword { -fx-font-weight: bold; }\n");
        css.append(".root .code-area .comment, .root .code-area .comment-line, .root .code-area .comment-multi, .root .code-area .comment-javadoc { -fx-font-style: italic; }\n");
        css.append(".root .code-area .lineno .text { -fx-fill: -color-fg-muted; }\n");
        return css.toString();
    }

    private static void rule(StringBuilder css, String color, String... styles) {
        for (int i = 0; i < styles.length; i++) {
            if (i > 0) css.append(", ");
            css.append(".root .code-area .").append(styles[i]);
        }
        css.append(" { -fx-fill: ").append(color).append("; }\n");
    }

    @Override
    public String toString() { return label; }
}
