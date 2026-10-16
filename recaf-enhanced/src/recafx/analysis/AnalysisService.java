package recafx.analysis;

import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.ResourceJvmClassListener;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the network + obfuscation analyses for the current workspace and keeps the results around
 * for highlighting, notes and reports. Obfuscation results are "sticky" (computed from the class state
 * when the workspace was opened) so a class that was obfuscated stays flagged after deobfuscation.
 * Network results follow the current class state so decrypted URLs show up after deobfuscation.
 */
public class AnalysisService {
	private static final Logger logger = Logging.get(AnalysisService.class);
	private final ExecutorService pool = Executors.newFixedThreadPool(
			Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1)), r -> {
				Thread t = new Thread(r, "recafx-analysis");
				t.setDaemon(true);
				return t;
			});
	private final Map<String, NetworkAnalysis.ClassFindings> network = new ConcurrentHashMap<>();
	private final Map<String, ObfuscationAnalysis.ClassReport> obfuscation = new ConcurrentHashMap<>();
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private volatile ObfuscationAnalysis.Report report = ObfuscationAnalysis.Report.empty();
	private volatile Workspace workspace;
	private volatile boolean complete;
	private long generation;
	private final Map<JvmClassInfo, NetworkAnalysis.ClassFindings> networkCache = new IdentityHashMap<>();
	private final ArrayDeque<JvmClassInfo> cacheOrder = new ArrayDeque<>();
	private static final int MAX_CACHED_CLASSES = 8192;
	private final ScheduledExecutorService notifications = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "recafx-analysis-notifications");
		thread.setDaemon(true);
		return thread;
	});
	private boolean notificationPending;
	private boolean closed;
	private final Map<String, JvmClassInfo> latestNetworkInput = new ConcurrentHashMap<>();
	private final ResourceJvmClassListener classListener = new ResourceJvmClassListener() {
		@Override
		public void onNewClass(WorkspaceResource resource, JvmClassBundle bundle, JvmClassInfo cls) {
			refreshNetwork(cls);
		}

		@Override
		public void onUpdateClass(WorkspaceResource resource, JvmClassBundle bundle, JvmClassInfo oldCls, JvmClassInfo newCls) {
			refreshNetwork(newCls);
			if (!obfuscation.containsKey(newCls.getName())) {
				// Renamed class: carry the sticky obfuscation report over to the new name.
				ObfuscationAnalysis.ClassReport old = obfuscation.get(oldCls.getName());
				if (old != null)
					obfuscation.put(newCls.getName(), new ObfuscationAnalysis.ClassReport(newCls.getName(), old.score(), old.reasons(), old.fingerprints()));
			}
		}

		@Override
		public void onRemoveClass(WorkspaceResource resource, JvmClassBundle bundle, JvmClassInfo cls) {
			synchronized (AnalysisService.this) {
				latestNetworkInput.remove(cls.getName());
				network.remove(cls.getName());
			}
			notifyListeners();
		}
	};

	public void addListener(Runnable listener) {listeners.add(listener);}
	public void removeListener(Runnable listener) {listeners.remove(listener);}

	/** Carries sticky obfuscation reports over when classes are renamed (mappings applied). */
	public void onClassesRenamed(Map<String, String> preToPost) {
		for (Map.Entry<String, String> e : preToPost.entrySet()) {
			if (e.getKey().equals(e.getValue())) continue;
			ObfuscationAnalysis.ClassReport old = obfuscation.get(e.getKey());
			if (old != null)
				obfuscation.put(e.getValue(), new ObfuscationAnalysis.ClassReport(e.getValue(), old.score(), old.reasons(), old.fingerprints()));
			NetworkAnalysis.ClassFindings net = network.remove(e.getKey());
			if (net != null) network.put(e.getValue(), new NetworkAnalysis.ClassFindings(e.getValue(), net.hits()));
		}
		notifyListeners();
	}

	public Workspace getWorkspace() {return workspace;}

	public boolean isComplete() {return complete;}

	public ObfuscationAnalysis.Report getReport() {return report;}

	public NetworkAnalysis.ClassFindings getNetwork(String className) {return network.get(className);}

	public ObfuscationAnalysis.ClassReport getObfuscation(String className) {return obfuscation.get(className);}

	public Collection<NetworkAnalysis.ClassFindings> getAllNetwork() {return network.values();}

	public boolean isNetworkClass(String className) {
		NetworkAnalysis.ClassFindings f = network.get(className);
		return f != null && !f.isEmpty();
	}

	public boolean isObfuscatedClass(String className) {
		ObfuscationAnalysis.ClassReport r = obfuscation.get(className);
		return r != null && r.obfuscated();
	}

	public int networkClassCount() {
		int n = 0;
		for (NetworkAnalysis.ClassFindings f : network.values()) if (!f.isEmpty()) n++;
		return n;
	}

	public synchronized void clear() {
		generation++;
		Workspace old = workspace;
		if (old != null)
			try {
				old.getPrimaryResource().removeResourceJvmClassListener(classListener);
			} catch (Throwable ignored) {}
		workspace = null;
		network.clear();
		networkCache.clear();
		cacheOrder.clear();
		latestNetworkInput.clear();
		obfuscation.clear();
		report = ObfuscationAnalysis.Report.empty();
		complete = false;
		notifyListeners();
	}

	/** Analyzes every JVM class of the primary resource. Returns a future completing with the obfuscation report. */
	public synchronized CompletableFuture<ObfuscationAnalysis.Report> analyze(Workspace workspace) {
		clear();
		this.workspace = workspace;
		long ticket = generation;
		List<JvmClassInfo> classes = new ArrayList<>();
		WorkspaceResource primary = workspace.getPrimaryResource();
		primary.jvmAllClassBundleStreamRecursive().forEach(bundle -> classes.addAll(bundle.values()));
		primary.addResourceJvmClassListener(classListener);
		AtomicInteger remaining = new AtomicInteger(classes.size());
		CompletableFuture<ObfuscationAnalysis.Report> future = new CompletableFuture<>();
		if (classes.isEmpty()) {
			complete = true;
			future.complete(report);
			notifyListeners();
			return future;
		}
		for (JvmClassInfo cls : classes) {
			latestNetworkInput.put(cls.getName(), cls);
			pool.execute(() -> {
				try {
					if (this.workspace != workspace) return;
					org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
					try { cls.getClassReader().accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES); }
					catch (Throwable malformed) { node = null; }
					var net = NetworkAnalysis.analyze(cls, node);
					var obf = ObfuscationAnalysis.analyzeClass(cls, node);
					synchronized (this) {
						if (ticket != generation) return;
						cacheNetwork(cls, net);
						if (latestNetworkInput.get(cls.getName()) == cls) network.put(cls.getName(), net);
						obfuscation.put(cls.getName(), obf);
					}
				} catch (Throwable t) {
					logger.warn("Analysis failed for {}", cls.getName(), t);
				} finally {
					if (remaining.decrementAndGet() == 0) {
						synchronized (this) {
						if (ticket != generation) { future.cancel(false); return; }
						report = ObfuscationAnalysis.summarize(obfuscation.values());
						complete = true;
						logger.info("LightningRecaf: analyzed {} classes, {} obfuscated, {} network related, obfuscator={}",
								classes.size(), report.obfuscatedCount(), networkClassCount(), report.obfuscator());
						future.complete(report);
						notifyListeners();
						}
					}
				}
			});
		}
		return future;
	}

	private synchronized void refreshNetwork(JvmClassInfo cls) {
		if (closed || workspace == null) return;
		long ticket = generation;
		latestNetworkInput.put(cls.getName(), cls);
		NetworkAnalysis.ClassFindings cached = networkCache.get(cls);
		if (cached != null) {
			network.put(cls.getName(), cached);
			notifyListeners();
			return;
		}
		pool.execute(() -> {
			try {
				synchronized (this) {
					if (ticket != generation || latestNetworkInput.get(cls.getName()) != cls) return;
				}
				var findings = NetworkAnalysis.analyze(cls);
				synchronized (this) {
					if (ticket != generation || latestNetworkInput.get(cls.getName()) != cls) return;
					cacheNetwork(cls, findings);
					network.put(cls.getName(), findings);
				}
				notifyListeners();
			} catch (Throwable ignored) {}
		});
	}
	private void cacheNetwork(JvmClassInfo cls, NetworkAnalysis.ClassFindings findings) {
		if (networkCache.put(cls, findings) != null) return;
		cacheOrder.addLast(cls);
		while (cacheOrder.size() > MAX_CACHED_CLASSES) networkCache.remove(cacheOrder.removeFirst());
	}
	public synchronized void close() {
		closed = true;
		clear();
		pool.shutdownNow();
		notifications.shutdownNow();
		listeners.clear();
	}

	private synchronized void notifyListeners() {
		if (closed || notificationPending) return;
		notificationPending = true;
		notifications.schedule(() -> {
			synchronized (this) { notificationPending = false; if (closed) return; }
			deliverNotifications();
		}, 100, TimeUnit.MILLISECONDS);
	}

	private void deliverNotifications() {
		for (Runnable listener : listeners)
			try {
				listener.run();
			} catch (Throwable t) {
				logger.warn("Analysis listener failed", t);
			}
	}
}
