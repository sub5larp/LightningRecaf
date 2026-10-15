package recafx.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.config.ConfigContainer;
import software.coley.recaf.config.ConfigValue;
import software.coley.recaf.services.config.ConfigManager;
import software.coley.recaf.services.file.RecafDirectoriesConfig;
import software.coley.recaf.services.json.GsonProvider;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Plugins register their config containers after Recaf already loaded persisted values, so this restores a
 * container's values from its JSON file the same way {@link ConfigManager#load()} would.
 */
public final class ConfigRestore {
	private static final Logger logger = Logging.get(ConfigRestore.class);

	private ConfigRestore() {}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void restore(RecafDirectoriesConfig directories, GsonProvider gsonProvider, ConfigContainer container) {
		Path path = directories.getConfigDirectory().resolve(container.getGroupAndId() + ".json");
		if (!Files.exists(path)) return;
		try {
			Gson gson = gsonProvider.getGson();
			JsonObject json = gson.fromJson(Files.readString(path), JsonObject.class);
			if (json == null) return;
			for (ConfigValue value : container.getValues().values()) {
				JsonElement element = json.get(value.getId());
				if (element == null || element.isJsonNull()) continue;
				try {
					value.setValue(gson.fromJson(element, value.getType()));
				} catch (Exception ex) {
					logger.warn("Could not restore {}.{}", container.getGroupAndId(), value.getId(), ex);
				}
			}
		} catch (Exception ex) {
			logger.warn("Could not restore config {}", container.getGroupAndId(), ex);
		}
	}
}
