package recafx.deobf;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import recafx.EnhancedConfig;
import recafx.analysis.AnalysisService;
import recafx.analysis.ObfuscationAnalysis;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.comment.ClassComments;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.comment.WorkspaceComments;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.IllegalNameMappingTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.Mappings;
import software.coley.recaf.services.mapping.gen.MappingGenerator;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.services.transform.TransformationParameters;
import software.coley.recaf.services.transform.TransformationPhase;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs the automatic pipeline: analysis, every applicable transformer until nothing changes, name
 * normalization and per-class notes.
 */
public class AutoDeobfuscator {
	private static final Logger logger = Logging.get(AutoDeobfuscator.class);
	public static final String NOTE_MARKER = "[LightningRecaf]";

	public enum Phase {IDLE, ANALYZING, TRANSFORMING, NORMALIZING, NOTES, DONE, CANCELLED, SKIPPED, FAILED}

	public record Progress(Phase phase, String message, double fraction) {
		public boolean running() {
			return phase == Phase.ANALYZING || phase == Phase.TRANSFORMING || phase == Phase.NORMALIZING || phase == Phase.NOTES;
		}
	}

	private final TransformationManager transformationManager;
	private final TransformationApplierService applierService;
	private final MappingApplierService mappingApplierService;
	private final MappingGenerator mappingGenerator;
	private final InheritanceGraphService inheritanceGraphService;
	private final CommentManager commentManager;
	private final AnalysisService analysis;
	private final ViewStateManager viewState;
	private final EnhancedConfig config;
	private final List<Consumer<Progress>> listeners = new CopyOnWriteArrayList<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "recafx-deobfuscation");
		t.setDaemon(true);
		return t;
	});
	private volatile Progress progress = new Progress(Phase.IDLE, "", -1);
	private volatile boolean cancelRequested;
	private final AtomicLong generation = new AtomicLong();
	private final AtomicBoolean manualQueued = new AtomicBoolean();
	private volatile Workspace activeWorkspace;
	private final ThreadLocal<Long> runTicket = new ThreadLocal<>();
	private volatile String lastReport = "No analysis run yet.";
	public String getLastReport() { return lastReport; }
	public synchronized void onWorkspaceClosed() {
		cancelRequested = true;
		activeWorkspace = null;
		generation.incrementAndGet();
		lastReport = "No active workspace.";
		report(Phase.IDLE, "", -1);
	}

	public AutoDeobfuscator(TransformationManager transformationManager, TransformationApplierService applierService,
	                        MappingApplierService mappingApplierService, MappingGenerator mappingGenerator,
	                        InheritanceGraphService inheritanceGraphService, CommentManager commentManager,
	                        AnalysisService analysis, ViewStateManager viewState, EnhancedConfig config) {
		this.transformationManager = transformationManager;
		this.applierService = applierService;
		this.mappingApplierService = mappingApplierService;
		this.mappingGenerator = mappingGenerator;
		this.inheritanceGraphService = inheritanceGraphService;
		this.commentManager = commentManager;
		this.analysis = analysis;
		this.viewState = viewState;
		this.config = config;
	}

	public void addListener(Consumer<Progress> listener) {listeners.add(listener);}

	public Progress getProgress() {return progress;}

	public ExecutorService getExecutor() {return executor;}

	public void cancel() {cancelRequested = true;}

	/** Entry point when a workspace is opened: analyze, then (if configured) deobfuscate automatically. */
	public synchronized void onWorkspaceOpened(Workspace workspace) {
		if (activeWorkspace == workspace) return;
		long ticket = generation.incrementAndGet();
		activeWorkspace = workspace;
		lastReport = "Analysis pending for the current workspace.";
		executor.execute(() -> {
			runTicket.set(ticket);
			try {
				if (generation.get() != ticket) return;
				cancelRequested = false;
				viewState.begin(workspace);
				report(Phase.ANALYZING, "Analyzing classes...", -1);
				if (generation.get() != ticket || cancelRequested) return;
				ObfuscationAnalysis.Report report = analysis.analyze(workspace).get();
				if (generation.get() != ticket || cancelRequested) return;
				if (!config.getAutoDeobfuscate().getValue()) {
					if (config.getAddNotes().getValue()) addNotes(workspace, report, Map.of(), 0);
					lastReport = summaryOf(report) + "\nAutomatic transformation disabled.";
					report(Phase.SKIPPED, summaryOf(report) + " (auto-deobfuscation disabled)", -1);
					return;
				}
				int classCount = viewState.getState() == null ? 0 : viewState.getState().originalCount();
				if (classCount > config.getMaxAutoClasses().getValue()) {
					if (config.getAddNotes().getValue()) addNotes(workspace, report, Map.of(), 0);
					lastReport = summaryOf(report) + "\nAutomatic class-count limit reached; use Deobfuscate now.";
					report(Phase.SKIPPED, summaryOf(report) + " - " + classCount + " classes exceed the automatic limit, use Lightning > Deobfuscate now", -1);
					return;
				}
				if (config.getOnlyWhenDetected().getValue() && !report.anyObfuscated()) {
					lastReport = "Automatic recovery skipped because 'Only when detected' is enabled and no pattern passed detection. " +
							"Use Deobfuscate now to attempt recovery anyway.";
					report(Phase.SKIPPED, "Automatic recovery skipped: no detection match. Use Deobfuscate now", -1);
					return;
				}
				runPipeline(workspace, report);
			} catch (Throwable t) {
				logger.error("Automatic deobfuscation failed", t);
				report(Phase.FAILED, "Deobfuscation failed: " + t.getMessage(), -1);
			} finally {
				finishCancellation(workspace);
				runTicket.remove();
			}
		});
	}

	/** Manual trigger from the menu / status bar. */
	public CompletableFuture<Void> runNow(Workspace workspace) {
		CompletableFuture<Void> future = new CompletableFuture<>();
		if (progress.running() || !manualQueued.compareAndSet(false, true)) {
			Progress current = progress;
			logger.info("Manual recovery requested while another recovery job is running");
			if (current.running()) report(current.phase(), "Recovery is already running; wait for it to finish or cancel it", current.fraction());
			future.completeExceptionally(new IllegalStateException("An analysis is already running"));
			return future;
		}
		logger.info("Manual deobfuscation requested");
		long ticket = generation.get();
		executor.execute(() -> {
			runTicket.set(ticket);
			try {
				if (generation.get() != ticket || activeWorkspace != workspace) { future.cancel(false); return; }
				cancelRequested = false;
				report(Phase.ANALYZING, "Preparing manual deobfuscation...", -1);
				if (generation.get() != ticket || cancelRequested) { future.cancel(false); return; }
				if (viewState.getState() == null || viewState.getState().getWorkspace() != workspace)
					viewState.begin(workspace);
				ObfuscationAnalysis.Report report = analysis.isComplete() && analysis.getWorkspace() == workspace
						? analysis.getReport() : analysis.analyze(workspace).get();
				// Work from the deobfuscated (non-normalized) state so mappings do not stack.
				if (viewState.getState().hasDeobfuscation() && viewState.getState().getView() != ViewStateManager.View.DEOBFUSCATED)
					viewState.setView(ViewStateManager.View.DEOBFUSCATED);
				runPipeline(workspace, report);
				future.complete(null);
			} catch (Throwable t) {
				logger.error("Deobfuscation failed", t);
				report(Phase.FAILED, "Deobfuscation failed: " + t.getMessage(), -1);
				future.completeExceptionally(t);
			} finally {
				finishCancellation(workspace);
				runTicket.remove();
				manualQueued.set(false);
			}
		});
		return future;
	}

	private void finishCancellation(Workspace workspace) {
		Long ticket = runTicket.get();
		if (ticket != null && ticket.longValue() != generation.get()) return;
		if (activeWorkspace == workspace && cancelRequested && progress.running()) {
			lastReport = "Recovery cancelled. Use Deobfuscate now to retry.";
			report(Phase.CANCELLED, "Recovery cancelled; ready to run again", -1);
		}
	}

	private void runPipeline(Workspace workspace, ObfuscationAnalysis.Report report) throws Exception {
		if (cancelRequested || activeWorkspace != workspace) return;
		Set<String> obfuscatedClasses = new LinkedHashSet<>();
		Set<String> seenNames = new java.util.HashSet<>();
		Set<String> duplicateNames = new java.util.TreeSet<>();
		workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().forEach(bundle -> {
			for (String name : bundle.keySet()) if (!seenNames.add(name)) duplicateNames.add(name);
		});
		for (ObfuscationAnalysis.ClassReport cr : report.classes().values())
			if (cr.obfuscated()) obfuscatedClasses.add(cr.className());
		Map<String,List<String>> referenceNotes=Map.of();
		if(config.getAddNotes().getValue() && duplicateNames.isEmpty()) {
			report(Phase.ANALYZING,"Inspecting ZKM strings and reference metadata...",-1);
			var metadataGraph=inheritanceGraphService.getOrCreateInheritanceGraph(workspace);
			referenceNotes=new java.util.LinkedHashMap<>(ZkmReferenceDiagnostics.scan(workspace,metadataGraph,()->cancelRequested||activeWorkspace!=workspace));
			for(var entry:ZkmStringDiagnostics.scan(workspace,metadataGraph,()->cancelRequested||activeWorkspace!=workspace).entrySet())
				referenceNotes.computeIfAbsent(entry.getKey(),ignored->new ArrayList<>()).addAll(entry.getValue());
		}

		// ----- Transformers --------------------------------------------------------------------------
		List<Class<? extends JvmClassTransformer>> transformers = new ArrayList<>();
		for (Class<? extends JvmClassTransformer> type : transformationManager.getJvmClassTransformers()) {
			if (!TransformSafety.automatic(type) && !(config.getExtendedRecovery().getValue() && TransformSafety.extended(type))) continue;
			transformers.add(type);
		}
		transformers.sort(java.util.Comparator.comparing(Class::getName));
		Map<String, Object> params = new HashMap<>();
		params.put(CallResultInliningTransformer.KEY_EVALUATE_CLASS_INITIALIZERS, true);
		params.put(CallResultInliningTransformer.KEY_MAX_STEPS, 60_000);
		params.put("peephole.data.staticcollect.max-steps", 60_000);
		params.put("peephole.data.indyinline.max-steps", 60_000);

		int maxPasses = Math.max(1, Math.min(32, config.getMaxPasses().getValue()));
		TransformationApplier applier = applierService.newApplier(workspace);
		applier.setMaxPasses(maxPasses);
		applier.setDropFaultyClasses(false);
		AtomicInteger transformed = new AtomicInteger();
		AtomicInteger failures = new AtomicInteger();
		Set<String> failedPairs = java.util.concurrent.ConcurrentHashMap.newKeySet();
		Map<String, Set<String>> perClassTransformers = new TreeMap<>();
		TransformationFeedback feedback = new TransformationFeedback() {
			@Override
			public void onPhaseStart(@Nonnull TransformationPhase phase, int phaseIndex, int phaseCount) {
				report(Phase.TRANSFORMING, "Deobfuscating with " + transformers.size() + " transformers (" + maxPasses + " passes max)...", -1);
			}

			@Override
			public void onTransformed(@Nonnull Workspace ws, @Nonnull WorkspaceResource resource,
			                          @Nonnull ClassBundle<?> bundle, @Nonnull ClassInfo classInfo,
			                          @Nonnull ClassTransformer transformer, int pass) {
				int n = transformed.incrementAndGet();
				synchronized (perClassTransformers) {
					perClassTransformers.computeIfAbsent(classInfo.getName(), k -> new LinkedHashSet<>()).add(transformer.identifier());
				}
				if (n % 25 == 0)
					report(Phase.TRANSFORMING, "Deobfuscating... pass " + pass + ", " + n + " class transformations", -1);
			}

			@Override
			public void onTransformFailure(@Nonnull Workspace ws, @Nonnull WorkspaceResource resource,
			                               @Nonnull ClassBundle<?> bundle, @Nonnull ClassInfo classInfo,
			                               @Nonnull ClassTransformer transformer, int pass, @Nonnull Throwable error) {
				failures.incrementAndGet();
				failedPairs.add(classInfo.getName() + '|' + transformer.identifier());
			}

			@Override
			public boolean shouldTransform(@Nonnull Workspace ws, @Nonnull WorkspaceResource resource,
			                               @Nonnull ClassBundle<?> bundle, @Nonnull ClassInfo classInfo,
			                               @Nonnull ClassTransformer transformer, int pass) {
				// A transformer that blew up on a class will blow up again next pass; skip it instead of spamming the log.
				return activeWorkspace == workspace && !cancelRequested
						&& !duplicateNames.contains(classInfo.getName())
						&& (duplicateNames.isEmpty() || !transformer.getClass().getSimpleName().equals("ZkmParameterUnpackingTransformer"))
						&& (!(transformer instanceof JvmClassTransformer jvm) || !(classInfo instanceof software.coley.recaf.info.JvmClassInfo cls) || TransformSafety.shouldEvaluate(jvm, cls))
						&& !failedPairs.contains(classInfo.getName() + '|' + transformer.identifier());
			}

			@Override
			public boolean hasRequestedCancellation() {
				return cancelRequested || activeWorkspace != workspace;
			}
		};
		report(Phase.TRANSFORMING, "Deobfuscating...", -1);
		JvmTransformResult result = applier.transformJvm(transformers, new TransformationParameters(params), feedback);
		if (cancelRequested || activeWorkspace != workspace) {
			report(Phase.CANCELLED, "Cancelled; transformation result discarded", -1);
			return;
		}
		// Do not apply a class if a transformation left invalid stack/data-flow structure.
		List<String> rejected = new ArrayList<>();
		result.getTransformedClasses().entrySet().removeIf(entry -> {
			String reason = TransformSafety.rejection(entry.getValue());
			if (reason == null) return false;
			rejected.add(entry.getKey().getValue().getName() + ": " + reason);
			perClassTransformers.remove(entry.getKey().getValue().getName());
			return true;
		});
		// Automatic passes may not delete classes or apply hidden name mappings.
		if (!result.getClassesToRemove().isEmpty() || !result.getMappingsToApply().isEmpty())
			throw new IllegalStateException("Automatic pass requested deletion or implicit renaming; result discarded");
		int modifiedClasses = result.getTransformedClasses().size();
		int removedClasses = result.getClassesToRemove().size();
		if (!viewState.commit(workspace, () -> { result.apply(); viewState.recordDeobfuscated(); })) return;
		lastReport = "LightningRecaf analysis\n" + summaryOf(report) + "\n"
				+ (duplicateNames.isEmpty() ? "" : "Duplicate names across versioned/embedded bundles retained unchanged: " + String.join(", ", duplicateNames) + ". Open an isolated variant to transform it.\n")
				+ (config.getExtendedRecovery().getValue() ? "EXTENDED RECOVERY: model-dependent calls and ZKM metadata invokedynamic enabled; inspect candidates carefully.\n" : "Conservative evaluation: audited closures; uncertain helpers retained.\n")
				+ transformers.size() + " reviewed transformers; maximum " + maxPasses + " passes\n"
				+ modifiedClasses + " classes accepted; " + rejected.size() + " candidates rejected by structural verification\n"
				+ "Original input files were not written. Verification does not prove runtime equivalence.\n"
				+ String.join("\n", rejected) + "\nTransformer failures: " + (failedPairs.isEmpty() ? "none" : String.join(", ", failedPairs))
				+ "\n\nApplied transforms by class";
		for (var entry : perClassTransformers.entrySet())
			lastReport += "\n" + entry.getKey() + "\n  " + String.join(", ", entry.getValue());
		if(!referenceNotes.isEmpty()) {
			lastReport+="\n\nZKM candidates from bounded initial-state interpretation of the input view. Diagnostics do not rewrite calls or prove later cache, initialization or linkage state.\n";
			for(var entry:referenceNotes.entrySet())lastReport+=entry.getKey()+"\n  "+String.join("\n  ",entry.getValue())+"\n";
		}
		logger.info("LightningRecaf: deobfuscation modified {} classes, removed {}, {} transformer failures",
				modifiedClasses, removedClasses, failures.get());

		// ----- Notes (before renaming so the comment manager migrates them) ------------------------
		int noted = 0;
		if (config.getAddNotes().getValue()) {
			report(Phase.NOTES, "Adding notes...", -1);
			noted = addNotes(workspace, report, perClassTransformers, maxPasses,referenceNotes);
		}

		// ----- Name normalization ------------------------------------------------------------------
		int renamed = 0;
		if (config.getNormalizeNames().getValue() && duplicateNames.isEmpty() && !cancelRequested && activeWorkspace == workspace) {
			report(Phase.NORMALIZING, "Normalizing names...", -1);
			InheritanceGraph graph = inheritanceGraphService.getOrCreateInheritanceGraph(workspace);
			// In an obfuscated workspace, minified single-letter names are renamed everywhere in the primary resource,
			// not only in the classes that scored above the threshold (a class full of a()/b() calls scores low on its own).
			boolean workspaceObfuscated = report.anyObfuscated();
			NormalizationFilter filter = new NormalizationFilter(
					name -> workspaceObfuscated || obfuscatedClasses.contains(name), config.getRenameMinified().getValue());
			NormalizingNameGenerator generator = new NormalizingNameGenerator();
			Mappings mappings = mappingGenerator.generate(workspace, workspace.getPrimaryResource(), graph, generator, filter);
			IntermediateMappings intermediate = mappings.exportIntermediate();
			if (!intermediate.isEmpty()) {
				MappingResults results = mappingApplierService.inWorkspace(workspace).applyToPrimaryResource(intermediate);
				if (!viewState.commit(workspace, results::apply)) return;
				IntermediateMappings applied = results.getMappings().exportIntermediate();
				renamed = applied.getClasses().size();
				for (List<?> l : applied.getFields().values()) renamed += l.size();
				for (List<?> l : applied.getMethods().values()) renamed += l.size();
				if (!viewState.commit(workspace, () -> viewState.recordNormalization(applied))) return;
			} else {
				viewState.commit(workspace, () -> viewState.recordNormalization(null));
			}
		}

		String summary = (!knownFamily(report) ? "No known family fingerprint" : "Possible " + report.obfuscator() + " (heuristic score " + report.confidence() + "/100)")
				+ ": " + modifiedClasses + " classes transformed" + (removedClasses > 0 ? ", " + removedClasses + " removed" : "")
				+ (renamed > 0 ? ", " + renamed + " names normalized" : "")
				+ (noted > 0 ? ", " + noted + " notes" : "")
				+ (rejected.isEmpty() ? "" : ", " + rejected.size() + " invalid candidates rejected")
				+ (failures.get() > 0 ? ", " + failures.get() + " transformer errors (see log)" : "");
		lastReport += "\n" + summary;
		report(Phase.DONE, summary, -1);
	}

	private int addNotes(Workspace workspace, ObfuscationAnalysis.Report report,
	                     Map<String, Set<String>> perClassTransformers, int maxPasses) {
		return addNotes(workspace,report,perClassTransformers,maxPasses,Map.of());
	}
	private int addNotes(Workspace workspace, ObfuscationAnalysis.Report report,
	                     Map<String, Set<String>> perClassTransformers, int maxPasses,Map<String,List<String>> referenceNotes) {
		WorkspaceComments comments = commentManager.getOrCreateWorkspaceComments(workspace);
		Set<String> targets = new LinkedHashSet<>(perClassTransformers.keySet());
		targets.addAll(referenceNotes.keySet());
		for (ObfuscationAnalysis.ClassReport cr : report.classes().values())
			if (cr.obfuscated()) targets.add(cr.className());
		int count = 0;
		String header = NOTE_MARKER + " Workspace: " + (!knownFamily(report) ? "no obfuscator fingerprint" : "possible " + report.obfuscator() + ", heuristic score " + report.confidence() + "/100");
		for (String className : targets) {
			if (cancelRequested || activeWorkspace != workspace) break;
			ClassPathNode path = workspace.findJvmClass(className);
			if (path == null) continue;
			ObfuscationAnalysis.ClassReport cr = report.classes().get(className);
			StringBuilder sb = new StringBuilder(header).append('\n');
			if (cr != null && cr.score() > 0)
				sb.append("Obfuscation score ").append(cr.score()).append(": ").append(cr.reasonSummary()).append('\n');
			else
				sb.append("No obfuscation indicators in this class itself.\n");
			Set<String> applied = perClassTransformers.get(className);
			if (applied != null && !applied.isEmpty()) {
				sb.append("Auto-deobfuscation applied: ");
				boolean first = true;
				for (String id : applied) {
					if (!first) sb.append(", ");
					first = false;
					sb.append(displayName(id));
				}
				sb.append('\n');
			} else {
				sb.append(maxPasses == 0 ? "Automatic transformation was skipped.\n" : "Auto-deobfuscation: no transformer changed this class.\n");
			}
			if(referenceNotes.containsKey(className)) {
				sb.append(String.join("\n",referenceNotes.get(className))).append('\n');
				sb.append("Diagnostic candidates from the input view; later cache, initialization and linkage state are not proved. Diagnostics do not rewrite calls.\n");
			}
			sb.append("Original name: ").append(className.replace('/', '.')).append('\n');
			sb.append("Switch between original / deobfuscated / normalized views in the status bar or the Lightning menu.");
			try {
				ClassComments cc = comments.getOrCreateClassComments(path);
				String existing = cc.getClassComment();
				if (existing != null && existing.contains(NOTE_MARKER)) {
					// Replace our previous note, keep anything the user wrote.
					int start = existing.indexOf(NOTE_MARKER);
					int end = existing.indexOf("\n\n", start);
					String user = (start > 0 ? existing.substring(0, start) : "") + (end >= 0 ? existing.substring(end + 2) : "");
					existing = user.isBlank() ? null : user.trim();
				}
				cc.setClassComment(existing == null ? sb.toString() : sb + "\n\n" + existing);
				count++;
			} catch (Throwable t) {
				logger.warn("Could not add note to {}", className, t);
			}
		}
		return count;
	}

	private static String displayName(String identifier) {
		String key = "deobf." + identifier;
		try {
			if (Lang.has(key)) return Lang.get(key);
		} catch (Throwable ignored) {}
		return switch (identifier) {
			case "lightning.skid.initialized-strings" -> "Skidfuscator initialized byte/pool strings";
			case "lightning.zkm.indexed-strings" -> "ZKM indexed string recovery";
			case "lightning.zkm.memoized-strings" -> "ZKM cached XOR/DES strings";
			case "lightning.zkm.des-numbers" -> "ZKM DES numeric constants";
			case "lightning.skid.opaque-identities" -> "Integer/long opaque identities";
			case "lightning.flow.integer-domain" -> "Known integer bits, ranges and polynomial predicates";
			case "lightning.flow.branch-constraints" -> "Path-sensitive integer branch constraints";
			case "lightning.skid.exception-edges" -> "Proven throw-null control flow";
			case "lightning.zkm.identity-rethrows" -> "ZKM identity rethrow bridges";
			case "lightning.strings.constant-indy-concat" -> "Constant invokedynamic concatenation";
			case UnreferencedPrivateMemberRemovingTransformer.IDENTIFIER -> "Unreferenced private member removal";
			case StringBuilderFoldingTransformer.IDENTIFIER -> "String builder folding";
			case JunkClassRemovingTransformer.IDENTIFIER -> "Junk class removal";
			default -> identifier;
		};
	}

	private static String summaryOf(ObfuscationAnalysis.Report report) {
		if (!knownFamily(report)) return report.obfuscatedCount() + " classes with obfuscation indicators; no family fingerprint";
		return "Possible " + report.obfuscator() + " (heuristic score " + report.confidence() + "/100, " + report.obfuscatedCount() + " classes)";
	}
	private static boolean knownFamily(ObfuscationAnalysis.Report report) {
		return report.obfuscator() != null && !report.obfuscator().equals("Unknown obfuscator");
	}

	private void report(Phase phase, String message, double fraction) {
		Long ticket = runTicket.get();
		if (ticket != null && ticket.longValue() != generation.get()) return;
		Progress p = new Progress(phase, message, fraction);
		progress = p;
		for (Consumer<Progress> listener : listeners)
			try {
				listener.accept(p);
			} catch (Throwable t) {
				logger.warn("Progress listener failed", t);
			}
	}

	public static Collection<String> obfuscatedClassNames(ObfuscationAnalysis.Report report) {
		List<String> out = new ArrayList<>();
		for (ObfuscationAnalysis.ClassReport cr : report.classes().values()) if (cr.obfuscated()) out.add(cr.className());
		return out;
	}
}
