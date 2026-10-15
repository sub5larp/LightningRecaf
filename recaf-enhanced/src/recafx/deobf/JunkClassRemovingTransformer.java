package recafx.deobf;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import recafx.analysis.ObfuscationAnalysis;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes "phantom" classes that obfuscators (Skidfuscator, Bozar, Caesium, Radon, qProtect...) flood a jar
 * with. A class is only removed when nothing references it: no bytecode reference from any other class, no
 * string constant naming it, no mention in any text resource (fabric.mod.json, mixin configs, META-INF/services,
 * manifests...), no {@code main}, no annotations - and when it additionally looks like junk: an obfuscated name,
 * part of a flood of same-named classes in sibling packages, or an empty shell.
 */
public class JunkClassRemovingTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "recafx.cleanup.junk-classes";
	private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z_$][\\w$]*(?:[./][A-Za-z_$][\\w$]*)+");
	private static final Pattern SIMPLE_TOKEN = Pattern.compile("[A-Za-z_$][\\w$]*");
	private final Predicate<String> obfuscatedClass;
	private Set<String> referenced = Collections.emptySet();
	/** Unqualified names mentioned in text resources: mixin configs list classes relative to a package. */
	private Set<String> referencedSimpleNames = Collections.emptySet();
	private Map<String, Integer> simpleNameFloods = Collections.emptyMap();

	public JunkClassRemovingTransformer(Predicate<String> obfuscatedClass) {
		this.obfuscatedClass = obfuscatedClass == null ? n -> false : obfuscatedClass;
	}

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		Set<String> refs = new HashSet<>();
		Set<String> simpleRefs = new HashSet<>();
		Map<String, Integer> floods = new HashMap<>();
		WorkspaceResource primary = workspace.getPrimaryResource();
		primary.jvmAllClassBundleStreamRecursive().forEach(bundle -> bundle.forEach(info -> {
			try {
				// Bytecode references: everything in the constant pool that names a class.
				for (String ref : info.getReferencedClasses())
					if (!ref.equals(info.getName())) refs.add(ref);
				// Reflection / Class.forName style references in string constants.
				for (String s : info.getStringConstants()) addTokens(refs, s);
				// Outer/inner relations keep each other alive.
				if (info.getOuterClassName() != null) refs.add(info.getOuterClassName());
				info.getInnerClasses().forEach(inner -> refs.add(inner.getInnerClassName()));
				String simple = info.getName().substring(info.getName().lastIndexOf('/') + 1);
				floods.merge(simple, 1, Integer::sum);
			} catch (Throwable ignored) {}
		}));
		// Text resources: mod descriptors, mixin configs, service files, manifests.
		primary.fileBundleStreamRecursive().forEach(bundle -> bundle.forEach(file -> {
			if (!isTextResource(file)) return;
			try {
				String text = new String(file.getRawContent(), StandardCharsets.UTF_8);
				if (text.length() < 2_000_000) {
					addTokens(refs, text);
					Matcher m = SIMPLE_TOKEN.matcher(text);
					while (m.find()) simpleRefs.add(m.group());
				}
			} catch (Throwable ignored) {}
		}));
		referenced = refs;
		referencedSimpleNames = simpleRefs;
		simpleNameFloods = floods;
	}

	private static boolean isTextResource(FileInfo file) {
		String name = file.getName().toLowerCase(Locale.ROOT);
		if (name.startsWith("meta-inf/services/") || name.endsWith("/manifest.mf") || name.equals("meta-inf/manifest.mf")) return true;
		return name.endsWith(".json") || name.endsWith(".txt") || name.endsWith(".xml") || name.endsWith(".properties")
				|| name.endsWith(".cfg") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".toml")
				|| name.endsWith(".mf") || name.endsWith(".html") || name.endsWith(".js") || name.endsWith(".kts")
				|| name.endsWith(".info") || name.endsWith(".list") || name.endsWith(".conf");
	}

	private static void addTokens(Set<String> refs, String text) {
		Matcher m = NAME_TOKEN.matcher(text);
		while (m.find()) {
			String token = m.group();
			refs.add(token.replace('.', '/'));
		}
	}

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		String name = initialClassState.getName();
		if (referenced.contains(name)) return;
		if (referencedSimpleNames.contains(simple(name))) return;
		// Keep anything that could be an entry point or framework-managed component.
		if (initialClassState.getDeclaredMethod("main", "([Ljava/lang/String;)V") != null) return;
		if (name.equals("module-info") || name.endsWith("/module-info") || name.endsWith("package-info")) return;
		ClassNode node = context.getNode(bundle, initialClassState);
		if (node.visibleAnnotations != null || node.invisibleAnnotations != null) return;
		if (node.outerClass != null || name.indexOf('$') >= 0) return;

		// Only the name itself decides "junk-looking"; a readable class that merely scored as obfuscated is kept.
		String simple = simple(name);
		boolean junkName = ObfuscationAnalysis.isIllegalClassName(name) || ObfuscationAnalysis.isConfusable(simple);
		boolean flood = simpleNameFloods.getOrDefault(simple, 0) >= 5 && !simple.equals("package-info");
		boolean shell = node.fields.isEmpty() && node.methods.stream().allMatch(m -> m.name.equals("<init>") || isTrivial(m));
		// Classes that extend something or implement interfaces are likely framework hooks (mixins, listeners); keep unless flooded.
		boolean plainObject = (node.superName == null || node.superName.equals("java/lang/Object")) && (node.interfaces == null || node.interfaces.isEmpty());
		if (!(flood || (plainObject && (junkName || shell)))) return;

		context.markClassForRemoval(initialClassState);
	}

	private static boolean isTrivial(MethodNode m) {
		if ((m.access & Opcodes.ACC_ABSTRACT) != 0) return true;
		if (m.instructions == null) return true;
		int real = 0;
		for (var insn : m.instructions) if (insn.getOpcode() >= 0) real++;
		return real <= 2;
	}

	private static String simple(String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1);
	}

	@Nonnull
	@Override
	public String identifier() {
		return IDENTIFIER;
	}
}
