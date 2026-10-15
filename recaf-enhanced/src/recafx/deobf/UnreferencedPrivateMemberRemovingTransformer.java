package recafx.deobf;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import recafx.analysis.ObfuscationAnalysis;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.DeadCodeRemovingTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.InvokeDynamicInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueInliningTransformer;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Removes private static helper members (typically string decryptors and their key tables) that are no
 * longer referenced by anything once their call sites have been inlined. Only members with obfuscated
 * looking names, or members in classes flagged as obfuscated, are considered so hand-written code
 * that happens to have an unused private helper is left alone.
 */
public class UnreferencedPrivateMemberRemovingTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "recafx.cleanup.unreferenced-private";
	private final Predicate<String> obfuscatedClass;
	private Set<String> referencedMembers = Collections.emptySet();

	public UnreferencedPrivateMemberRemovingTransformer(Predicate<String> obfuscatedClass) {
		this.obfuscatedClass = obfuscatedClass == null ? n -> false : obfuscatedClass;
	}

	@Override
	public void setup(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace) {
		Set<String> refs = new HashSet<>();
		workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().forEach(bundle -> bundle.forEach(info -> {
			ClassNode node;
			try {
				node = context.getNode(bundle, info);
			} catch (Throwable t) {
				return;
			}
			for (MethodNode method : node.methods) {
				if (method.instructions == null) continue;
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode min) refs.add(min.owner + '.' + min.name + min.desc);
					else if (insn instanceof FieldInsnNode fin) refs.add(fin.owner + '.' + fin.name + ' ' + fin.desc);
					else if (insn instanceof InvokeDynamicInsnNode indy) {
						addHandle(refs, indy.bsm);
						for (Object arg : indy.bsmArgs) if (arg instanceof Handle h) addHandle(refs, h);
					} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) addHandle(refs, h);
				}
			}
		}));
		referencedMembers = refs;
	}

	private static void addHandle(Set<String> refs, Handle h) {
		if (h.getTag() >= Opcodes.H_INVOKEVIRTUAL) refs.add(h.getOwner() + '.' + h.getName() + h.getDesc());
		else refs.add(h.getOwner() + '.' + h.getName() + ' ' + h.getDesc());
	}

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean flagged = obfuscatedClass.test(node.name);
		boolean dirty = false;
		Iterator<MethodNode> methods = node.methods.iterator();
		while (methods.hasNext()) {
			MethodNode m = methods.next();
			if (!isCandidate(m.access, m.name, flagged)) continue;
			if (m.name.startsWith("<")) continue;
			if (referencedMembers.contains(node.name + '.' + m.name + m.desc)) continue;
			if (m.visibleAnnotations != null || m.invisibleAnnotations != null) continue;
			methods.remove();
			dirty = true;
		}
		Iterator<FieldNode> fields = node.fields.iterator();
		while (fields.hasNext()) {
			FieldNode f = fields.next();
			if (!isCandidate(f.access, f.name, flagged)) continue;
			if (f.name.equals("serialVersionUID") || (f.access & Opcodes.ACC_ENUM) != 0) continue;
			if (referencedMembers.contains(node.name + '.' + f.name + ' ' + f.desc)) continue;
			if (f.visibleAnnotations != null || f.invisibleAnnotations != null) continue;
			// A static field that is still written in <clinit> but never read is dead, but removing it would
			// leave a dangling PUTSTATIC. Skip unless no instruction in this class touches it.
			if (touchedInClass(node, f)) continue;
			fields.remove();
			dirty = true;
		}
		if (dirty) {
			context.setRecomputeFrames(node.name);
			context.setNode(bundle, initialClassState, node);
		}
	}

	private static boolean touchedInClass(ClassNode node, FieldNode f) {
		for (MethodNode m : node.methods) {
			if (m.instructions == null) continue;
			for (AbstractInsnNode insn : m.instructions)
				if (insn instanceof FieldInsnNode fin && fin.owner.equals(node.name) && fin.name.equals(f.name) && fin.desc.equals(f.desc))
					return true;
		}
		return false;
	}

	private static boolean isCandidate(int access, String name, boolean classFlagged) {
		if ((access & Opcodes.ACC_PRIVATE) == 0 || (access & Opcodes.ACC_STATIC) == 0) return false;
		if ((access & Opcodes.ACC_SYNTHETIC) != 0) return true;
		if (classFlagged) return true;
		return ObfuscationAnalysis.isIllegalName(name) || ObfuscationAnalysis.isConfusable(name) || ObfuscationAnalysis.isSingleLetter(name);
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(CallResultInliningTransformer.class, InvokeDynamicInliningTransformer.class,
				StaticValueInliningTransformer.class, DeadCodeRemovingTransformer.class);
	}

	@Nonnull
	@Override
	public String identifier() {
		return IDENTIFIER;
	}
}
