package recafx.ui;

import jakarta.annotation.Nonnull;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.BorderPane;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.slf4j.Logger;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.path.DockContainerPath;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.BundlePathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.DirectoryPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.path.WorkspacePathNode;
import software.coley.recaf.services.cell.CellConfigurationService;
import software.coley.recaf.services.cell.context.ContextMenuProviderService;
import software.coley.recaf.services.cell.context.ContextSource;
import software.coley.recaf.services.cell.context.DirectoryContextMenuAdapter;
import software.coley.recaf.services.cell.context.PackageContextMenuAdapter;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.control.PathNodeTree;
import software.coley.recaf.ui.control.tree.WorkspaceTreeNode;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.util.Animations;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.ResourceFileListener;
import software.coley.recaf.workspace.model.resource.ResourceJvmClassListener;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * "Open in new explorer tab": shows a package or directory as its own tree tab next to the main workspace
 * explorer. Up to {@link #MAX_TABS} tabs, all backed by the same workspace.
 */
public class ExplorerTabs implements PackageContextMenuAdapter, DirectoryContextMenuAdapter, software.coley.recaf.services.cell.context.ClassContextMenuAdapter {
	public static final int MAX_TABS = 10;
	private static final Logger logger = Logging.get(ExplorerTabs.class);
	private final DockingManager dockingManager;
	private final CellConfigurationService cellConfigurationService;
	private final ContextMenuProviderService contextMenuProviderService;
	private final Actions actions;
	private final List<SubTree> open = new ArrayList<>();
	private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "recafx-explorer-tabs");
		t.setDaemon(true);
		return t;
	});

	public ExplorerTabs(DockingManager dockingManager, CellConfigurationService cellConfigurationService,
	                    ContextMenuProviderService contextMenuProviderService, Actions actions) {
		this.dockingManager = dockingManager;
		this.cellConfigurationService = cellConfigurationService;
		this.contextMenuProviderService = contextMenuProviderService;
		this.actions = actions;
	}

	public void install() {
		contextMenuProviderService.addPackageContextMenuAdapter(this);
		contextMenuProviderService.addDirectoryContextMenuAdapter(this);
		contextMenuProviderService.addClassContextMenuAdapter(this);
	}

	public int openCount() {return open.size();}

	@Override
	public void adaptPackageContextMenu(@Nonnull ContextMenu menu, @Nonnull ContextSource source, @Nonnull Workspace workspace,
	                                    @Nonnull WorkspaceResource resource, @Nonnull ClassBundle<? extends ClassInfo> bundle,
	                                    @Nonnull String packageName) {
		addItem(menu, () -> openTab(workspace, resource, bundle, packageName));
	}

	@Override
	public void adaptDirectoryContextMenu(@Nonnull ContextMenu menu, @Nonnull ContextSource source, @Nonnull Workspace workspace,
	                                      @Nonnull WorkspaceResource resource, @Nonnull FileBundle bundle, @Nonnull String directoryName) {
		addItem(menu, () -> openTab(workspace, resource, bundle, directoryName));
	}

	@Override
	public void adaptJvmClassMenu(@Nonnull ContextMenu menu, @Nonnull ContextSource source, @Nonnull Workspace workspace,
	                              @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle, @Nonnull JvmClassInfo info) {
		String pkg = info.getPackageName() == null ? "" : info.getPackageName();
		MenuItem item = new MenuItem("Open package in new explorer tab", new FontIconView(CarbonIcons.TREE_VIEW_ALT));
		item.setOnAction(e -> openTab(workspace, resource, bundle, pkg));
		if (!menu.getItems().isEmpty()) menu.getItems().add(new SeparatorMenuItem());
		menu.getItems().add(item);
	}

	private void addItem(ContextMenu menu, Runnable action) {
		MenuItem item = new MenuItem("Open in new explorer tab", new FontIconView(CarbonIcons.TREE_VIEW_ALT));
		item.setOnAction(e -> action.run());
		if (!menu.getItems().isEmpty()) menu.getItems().add(new SeparatorMenuItem());
		menu.getItems().add(item);
	}

	public void openTab(Workspace workspace, WorkspaceResource resource, ClassBundle<? extends ClassInfo> bundle, String packageName) {
		open(workspace, resource, bundle, packageName, true);
	}

	public void openTab(Workspace workspace, WorkspaceResource resource, FileBundle bundle, String directoryName) {
		open(workspace, resource, bundle, directoryName, false);
	}

	private void open(Workspace workspace, WorkspaceResource resource, software.coley.recaf.workspace.model.bundle.Bundle<?> bundle,
	                  String directory, boolean classes) {
		DockContainerLeaf leaf = explorerLeaf();
		if (leaf == null) {
			logger.warn("Workspace explorer container not found, cannot open explorer tab");
			return;
		}
		for (SubTree existing : open) {
			if (existing.workspace == workspace && existing.bundle == bundle && existing.directory.equals(directory)) {
				leaf.selectDockable(existing.dockable);
				return;
			}
		}
		if (open.size() >= MAX_TABS) {
			Dockable last = open.getLast().dockable;
			leaf.selectDockable(last);
			Animations.animateWarn(last.getNode(), 1200);
			logger.warn("Explorer tab limit ({}) reached", MAX_TABS);
			return;
		}
		SubTree subTree = new SubTree(workspace, resource, bundle, directory, classes);
		open.add(subTree);
		String title = directory.isEmpty() ? (classes ? "(default package)" : "(files)") : directory.substring(directory.lastIndexOf('/') + 1);
		Dockable dockable = dockingManager.newDockable(title, classes ? CarbonIcons.FOLDER : CarbonIcons.FOLDERS, subTree.pane);
		dockable.setTooltip(new Tooltip(directory.isEmpty() ? "Default package" : directory.replace('/', '.')));
		dockable.setDragGroupMask(DockingManager.GROUP_TOOLS);
		dockable.setClosable(true);
		dockable.addCloseListener((path, d) -> subTree.dispose());
		subTree.dockable = dockable;
		leaf.addDockable(dockable);
		leaf.selectDockable(dockable);
		subTree.installListeners();
		builder.execute(subTree::build);
	}

	private DockContainerLeaf explorerLeaf() {
		try {
			DockContainerPath path = dockingManager.getBento().search().container(DockingManager.ID_CONTAINER_WORKSPACE_TOOLS);
			if (path != null && path.tailContainer() instanceof DockContainerLeaf leaf) return leaf;
		} catch (Throwable t) {
			logger.warn("Explorer container lookup failed", t);
		}
		return null;
	}

	public void closeAll() {
		for (SubTree tree : new ArrayList<>(open)) {
			DockContainerLeaf leaf = explorerLeaf();
			if (leaf != null && tree.dockable != null) leaf.closeDockable(tree.dockable);
			tree.dispose();
		}
		open.clear();
	}

	public void onWorkspaceClosed(Workspace workspace) {
		FxThreadUtil.run(() -> {
		for (SubTree tree : new ArrayList<>(open))
			if (tree.workspace == workspace) {
				tree.dispose();
				FxThreadUtil.run(() -> {
					DockContainerLeaf leaf = explorerLeaf();
					if (leaf != null && tree.dockable != null) leaf.closeDockable(tree.dockable);
				});
			}
		});
	}
	public void dispose() {
		contextMenuProviderService.removePackageContextMenuAdapter(this);
		contextMenuProviderService.removeDirectoryContextMenuAdapter(this);
		contextMenuProviderService.removeClassContextMenuAdapter(this);
		closeAll();
		builder.shutdownNow();
	}

	/** One rooted tree view. */
	private class SubTree {
		final Workspace workspace;
		final WorkspaceResource resource;
		final software.coley.recaf.workspace.model.bundle.Bundle<?> bundle;
		final String directory;
		final boolean classes;
		final BorderPane pane = new BorderPane();
		final PathNodeTree tree;
		final TextField filter = new TextField();
		final WorkspaceTreeNode root;
		final DirectoryPathNode directoryPath;
		Dockable dockable;
		WorkspaceTreeNode directoryNode;
		ResourceJvmClassListener classListener;
		ResourceFileListener fileListener;
		volatile boolean disposed;
		boolean ready;
		final java.util.Queue<Runnable> pending = new java.util.ArrayDeque<>();

		SubTree(Workspace workspace, WorkspaceResource resource, software.coley.recaf.workspace.model.bundle.Bundle<?> bundle,
		        String directory, boolean classes) {
			this.workspace = workspace;
			this.resource = resource;
			this.bundle = bundle;
			this.directory = directory;
			this.classes = classes;
			tree = new PathNodeTree(cellConfigurationService, actions);
			tree.setShowRoot(false);
			WorkspacePathNode workspacePath = PathNodes.workspacePath(workspace);
			root = new WorkspaceTreeNode(workspacePath);
			directoryPath = PathNodes.directoryPath(workspace, resource, bundle, directory);
			filter.setPromptText("Filter " + (directory.isEmpty() ? "default package" : directory.replace('/', '.')));
			filter.getStyleClass().add("workspace-filter-text");
			filter.textProperty().addListener((ob, old, cur) -> applyFilter(cur));
			BorderPane filterPane = new BorderPane(filter);
			filterPane.getStyleClass().add("workspace-filter-pane");
			pane.setCenter(tree);
			pane.setBottom(filterPane);
		}

		void build() {
			try {
				if (disposed) return;
				String prefix = directory.isEmpty() ? "" : directory + "/";
				if (classes) {
					ClassBundle<?> classBundle = (ClassBundle<?>) bundle;
					for (ClassInfo info : classBundle.values())
						if (inScope(prefix, info.getPackageName()))
							WorkspaceTreeNode.getOrInsertIntoTree(root, PathNodes.classPath(workspace, resource, classBundle, info));
				} else {
					FileBundle fileBundle = (FileBundle) bundle;
					for (FileInfo info : fileBundle.values())
						if (inScope(prefix, info.getDirectoryName()))
							WorkspaceTreeNode.getOrInsertIntoTree(root, PathNodes.filePath(workspace, resource, fileBundle, info));
				}
				// Always have the directory node even when it is empty.
				directoryNode = WorkspaceTreeNode.getOrInsertIntoTree(root, directoryPath);
				FxThreadUtil.run(() -> {
					if (disposed) return;
					ready = true;
					while (!pending.isEmpty()) pending.remove().run();
					directoryNode = WorkspaceTreeNode.getOrInsertIntoTree(root, directoryPath);
					tree.setRoot(directoryNode);
					directoryNode.setExpanded(true);
					applyFilter(filter.getText());
				});
			} catch (Throwable t) {
				logger.error("Failed building explorer tab for {}", directory, t);
			}
		}

		private boolean inScope(String prefix, String dir) {
			if (dir == null) dir = "";
			if (prefix.isEmpty()) return !classes || dir.isEmpty();
			return dir.equals(directory) || dir.startsWith(prefix);
		}
		private void update(Runnable action) {
			FxThreadUtil.run(() -> {
				if (disposed) return;
				if (!ready) { pending.add(action); return; }
				action.run();
				WorkspaceTreeNode nextRoot = WorkspaceTreeNode.getOrInsertIntoTree(root, directoryPath);
				if (nextRoot != directoryNode) {
					directoryNode = nextRoot;
					tree.setRoot(nextRoot);
					nextRoot.setExpanded(true);
					applyFilter(filter.getText());
				}
			});
		}

		private void installListeners() {
			String prefix = directory.isEmpty() ? "" : directory + "/";
			if (classes) {
				classListener = new ResourceJvmClassListener() {
					@Override
					public void onNewClass(WorkspaceResource res, JvmClassBundle b, JvmClassInfo cls) {
						if (b == bundle && inScope(prefix, cls.getPackageName()))
							update(() -> WorkspaceTreeNode.getOrInsertIntoTree(root, PathNodes.classPath(workspace, res, b, cls)));
					}

					@Override
					public void onUpdateClass(WorkspaceResource res, JvmClassBundle b, JvmClassInfo oldCls, JvmClassInfo newCls) {
						if (b != bundle) return;
						update(() -> {
							WorkspaceTreeNode node = root.getNodeByPath(PathNodes.classPath(workspace, res, b, oldCls));
							if (node != null && oldCls.getName().equals(newCls.getName())) node.setValue(PathNodes.classPath(workspace, res, b, newCls));
							else {
								root.removeNodeByPath(PathNodes.classPath(workspace, res, b, oldCls));
								if (inScope(prefix, newCls.getPackageName()))
								WorkspaceTreeNode.getOrInsertIntoTree(root, PathNodes.classPath(workspace, res, b, newCls));
							}
						});
					}

					@Override
					public void onRemoveClass(WorkspaceResource res, JvmClassBundle b, JvmClassInfo cls) {
						if (b == bundle)
							update(() -> root.removeNodeByPath(PathNodes.classPath(workspace, res, b, cls)));
					}
				};
				resource.addResourceJvmClassListener(classListener);
			} else {
				fileListener = new ResourceFileListener() {
					@Override
					public void onNewFile(WorkspaceResource res, FileBundle b, FileInfo file) {
						if (b == bundle && inScope(prefix, file.getDirectoryName()))
							update(() -> WorkspaceTreeNode.getOrInsertIntoTree(root, PathNodes.filePath(workspace, res, b, file)));
					}

					@Override
					public void onUpdateFile(WorkspaceResource res, FileBundle b, FileInfo oldFile, FileInfo newFile) {
						if (b != bundle) return;
						update(() -> {
							WorkspaceTreeNode node = root.getNodeByPath(PathNodes.filePath(workspace, res, b, oldFile));
							if (node != null && oldFile.getName().equals(newFile.getName())) node.setValue(PathNodes.filePath(workspace, res, b, newFile));
							else {
								root.removeNodeByPath(PathNodes.filePath(workspace, res, b, oldFile));
								if (inScope(prefix, newFile.getDirectoryName()))
									WorkspaceTreeNode.getOrInsertIntoTree(root, PathNodes.filePath(workspace, res, b, newFile));
							}
						});
					}

					@Override
					public void onRemoveFile(WorkspaceResource res, FileBundle b, FileInfo file) {
						if (b == bundle)
							update(() -> root.removeNodeByPath(PathNodes.filePath(workspace, res, b, file)));
					}
				};
				resource.addResourceFileListener(fileListener);
			}
		}

		private void applyFilter(String text) {
			if (directoryNode == null) return;
			if (text == null || text.isBlank()) {
				directoryNode.predicateProperty().set(null);
				return;
			}
			String needle = text.toLowerCase();
			Predicate<TreeItem<PathNode<?>>> matcher = item -> {
				PathNode<?> node = item.getValue();
				String path = switch (node) {
					case DirectoryPathNode d -> d.getValue();
					case ClassPathNode c -> c.getValue().getName();
					case FilePathNode f -> f.getValue().getName();
					case null, default -> null;
				};
				if (path == null) return !item.getChildren().isEmpty();
				return path.toLowerCase().contains(needle);
			};
			directoryNode.predicateProperty().set(matcher);
		}

		void dispose() {
			if (disposed) return;
			disposed = true;
			pending.clear();
			open.remove(this);
			try {
				if (classListener != null) resource.removeResourceJvmClassListener(classListener);
				if (fileListener != null) resource.removeResourceFileListener(fileListener);
			} catch (Throwable ignored) {}
		}
	}

	@SuppressWarnings("unused")
	private static BundlePathNode bundlePathOf(DirectoryPathNode node) {
		return node.getParent();
	}
}
