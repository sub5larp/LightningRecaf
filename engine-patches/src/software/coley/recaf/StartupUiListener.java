package software.coley.recaf;

/** Optional plugin hook after scene registration, before the first visible frame. */
public interface StartupUiListener {
    void beforeMainWindowShown();
}
