package recafx.deobf;

import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.Info;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.services.mapping.BasicMappingsRemapper;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.data.ClassMapping;
import software.coley.recaf.services.mapping.data.FieldMapping;
import software.coley.recaf.services.mapping.data.MethodMapping;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the original / deobfuscated / normalized snapshots of the primary resource's classes and switches
 * the workspace between them, so the user can compare the states without losing anything.
 */
public class ViewStateManager {
	private static final Logger logger = Logging.get(ViewStateManager.class);

	public enum View {
		ORIGINAL("Original"), DEOBFUSCATED("Deobfuscated"), NORMALIZED("Deobfuscated + normalized names");

		public final String label;

		View(String label) {this.label = label;}
	}

	public static final class State {
		private final Workspace workspace;
		private final Map<String, JvmClassInfo> original = new LinkedHashMap<>();
		private final Map<View, Snapshot> snapshots = new EnumMap<>(View.class);
		private IntermediateMappings forward;
		private volatile Map<String, String> originalNames = Map.of();
		private volatile View view = View.ORIGINAL;

		State(Workspace workspace) {this.workspace = workspace;}

		public Workspace getWorkspace() {return workspace;}

		public View getView() {return view;}

		public boolean hasDeobfuscation() {return snapshots.containsKey(View.DEOBFUSCATED);}

		public boolean hasNormalization() {return forward != null && !forward.isEmpty();}

		public int originalCount() {return snapshots.get(View.ORIGINAL).classCount();}

		public JvmClassInfo getOriginal(String name) {return original.get(name);}

		public IntermediateMappings getForwardMappings() {return forward;}

		/** @return the pre-normalization name of a class, or the given name when it was not renamed. */
		public String originalNameOf(String currentName) {
			return originalNames.getOrDefault(currentName, currentName);
		}
	}

	private final MappingApplierService mappingApplierService;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile State state;
	private volatile boolean switching;

	public ViewStateManager(MappingApplierService mappingApplierService) {
		this.mappingApplierService = mappingApplierService;
	}

	public void addListener(Runnable listener) {listeners.add(listener);}

	public State getState() {return state;}
	public boolean isSwitching() {return switching;}

	public synchronized State begin(Workspace workspace) {
		State s = new State(workspace);
		workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().forEach(b -> {
			for (JvmClassInfo info : b.values()) s.original.putIfAbsent(info.getName(), info);
		});
		s.snapshots.put(View.ORIGINAL, Snapshot.capture(workspace));
		state = s;
		fire();
		return s;
	}

	public synchronized void clear() {
		state = null;
		fire();
	}

	public synchronized void recordDeobfuscated() {
		State s = state;
		if (s == null) return;
		s.snapshots.put(View.DEOBFUSCATED, Snapshot.capture(s.workspace));
		s.snapshots.remove(View.NORMALIZED);
		s.forward = null;
		s.originalNames = Map.of();
		s.view = View.DEOBFUSCATED;
		fire();
	}

	public synchronized void recordNormalization(IntermediateMappings forward) {
		State s = state;
		if (s == null) return;
		s.forward = forward;
		Map<String, String> originalNames = new LinkedHashMap<>();
		if (forward != null) forward.getClasses().values().forEach(cm -> originalNames.put(cm.getNewName(), cm.getOldName()));
		s.originalNames = Map.copyOf(originalNames);
		s.view = forward == null || forward.isEmpty() ? View.DEOBFUSCATED : View.NORMALIZED;
		s.snapshots.put(s.view, Snapshot.capture(s.workspace));
		fire();
	}

	/**
	 * Switches the workspace classes to the requested view. Must be called off the UI thread.
	 *
	 * @return {@code true} when the view changed.
	 */
	public synchronized boolean setView(View target) {
		State s = state;
		if (s == null || target == s.view) return false;
		if (target != View.ORIGINAL && !s.hasDeobfuscation()) return false;
		if (target == View.NORMALIZED && !s.hasNormalization()) return false;
		// Save edits in the outgoing view before changing anything. Originals used by analysis stay immutable.
		Snapshot outgoing = Snapshot.capture(s.workspace);
		View previous = s.view;
		s.snapshots.put(previous, outgoing);
		switching = true;
		fire();
		try {
			// Step 1: names back to the deobfuscated form if currently normalized.
			if (s.view == View.NORMALIZED) {
				applyMappings(s.workspace, inverse(s.forward));
				s.view = View.DEOBFUSCATED;
			}
			// Step 2: swap class bytes between original and deobfuscated.
			if (target == View.ORIGINAL && s.view == View.DEOBFUSCATED) {
				s.snapshots.get(View.ORIGINAL).restore();
				s.view = View.ORIGINAL;
			} else if (target != View.ORIGINAL && s.view == View.ORIGINAL) {
				s.snapshots.get(View.DEOBFUSCATED).restore();
				s.view = View.DEOBFUSCATED;
			}
			// Step 3: re-apply normalized names if requested.
			if (target == View.NORMALIZED && s.view == View.DEOBFUSCATED) {
				applyMappings(s.workspace, s.forward);
				s.view = View.NORMALIZED;
			}
			// Mappings notify navigation/comments; snapshots retain exact bytes and per-view edits.
			s.snapshots.get(target).restore();
			return true;
		} catch (Throwable t) {
			outgoing.restore();
			s.view = previous;
			logger.error("Failed switching view to {}", target, t);
			return false;
		} finally {
			switching = false;
			fire();
		}
	}

	private void applyMappings(Workspace workspace, IntermediateMappings mappings) {
		if (mappings == null || mappings.isEmpty()) return;
		MappingResults results = mappingApplierService.inWorkspace(workspace).applyToPrimaryResource(mappings);
		results.apply();
	}

	/** Serializes commits against workspace close/new-workspace events. */
	public synchronized boolean commit(Workspace expected, Runnable action) {
		if (state == null || state.workspace != expected) return false;
		action.run();
		return true;
	}

	private record Snapshot(Map<Bundle<Info>, Map<String, Info>> bundles) {
		static Snapshot capture(Workspace workspace) {
			Map<Bundle<Info>, Map<String, Info>> result = new IdentityHashMap<>();
			workspace.getPrimaryResource().<Info>bundleStreamRecursive()
					.forEach(b -> result.put(b, new LinkedHashMap<>(b)));
			return new Snapshot(result);
		}
		int classCount() {
			return bundles.values().stream().mapToInt(m -> (int) m.values().stream().filter(JvmClassInfo.class::isInstance).count()).sum();
		}
		void restore() {
			bundles.forEach((bundle, content) -> {
				for (String name : new ArrayList<>(bundle.keySet())) if (!content.containsKey(name)) bundle.remove(name);
				content.forEach((name, info) -> { if (bundle.get(name) != info) bundle.put(info); });
			});
		}
	}

	/** Builds mappings that undo {@code forward} (expressed in terms of the post-mapping names). */
	public static IntermediateMappings inverse(IntermediateMappings forward) {
		IntermediateMappings inverse = new IntermediateMappings();
		BasicMappingsRemapper remapper = new BasicMappingsRemapper(forward);
		for (ClassMapping cm : forward.getClasses().values())
			inverse.addClass(cm.getNewName(), cm.getOldName());
		for (Map.Entry<String, List<FieldMapping>> e : forward.getFields().entrySet()) {
			String newOwner = remapper.map(e.getKey());
			for (FieldMapping fm : new ArrayList<>(e.getValue())) {
				String desc = fm.getDesc() == null ? null : remapper.mapDesc(fm.getDesc());
				inverse.addField(newOwner, desc, fm.getNewName(), fm.getOldName());
			}
		}
		for (Map.Entry<String, List<MethodMapping>> e : forward.getMethods().entrySet()) {
			String newOwner = remapper.map(e.getKey());
			for (MethodMapping mm : new ArrayList<>(e.getValue()))
				inverse.addMethod(newOwner, remapper.mapMethodDesc(mm.getDesc()), mm.getNewName(), mm.getOldName());
		}
		return inverse;
	}

	private static JvmClassBundle bundle(Workspace workspace) {
		return workspace.getPrimaryResource().getJvmClassBundle();
	}

	private void fire() {
		for (Runnable listener : listeners)
			try {
				listener.run();
			} catch (Throwable t) {
				logger.warn("View listener failed", t);
			}
	}
}
