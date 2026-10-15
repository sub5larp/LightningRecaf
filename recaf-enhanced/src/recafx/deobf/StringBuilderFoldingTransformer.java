package recafx.deobf;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.deobfuscation.transform.generic.CallResultInliningTransformer;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Folds {@code new StringBuilder().append("a").append("b").toString()} chains made only of constant
 * strings (a common leftover once string decryption calls have been inlined) into a single constant.
 */
public class StringBuilderFoldingTransformer implements JvmClassTransformer {
	public static final String IDENTIFIER = "recafx.peephole.stringbuilder-fold";
	private static final String SB = "java/lang/StringBuilder";

	@Override
	public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
	                      @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle,
	                      @Nonnull JvmClassInfo initialClassState) throws TransformationException {
		ClassNode node = context.getNode(bundle, initialClassState);
		boolean dirty = false;
		for (MethodNode method : node.methods) {
			InsnList insns = method.instructions;
			if (insns == null || insns.size() == 0) continue;
			AbstractInsnNode insn = insns.getFirst();
			while (insn != null) {
				AbstractInsnNode next = insn.getNext();
				if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode tin && SB.equals(tin.desc)) {
					List<AbstractInsnNode> chain = matchChain(insn);
					if (chain != null) {
						StringBuilder folded = new StringBuilder();
						for (AbstractInsnNode c : chain)
							if (c instanceof LdcInsnNode ldc && ldc.cst instanceof String s) folded.append(s);
						AbstractInsnNode last = chain.getLast();
						next = last.getNext();
						insns.insertBefore(insn, new LdcInsnNode(folded.toString()));
						for (AbstractInsnNode c : chain) insns.remove(c);
						dirty = true;
					}
				}
				insn = next;
			}
		}
		if (dirty) {
			context.setRecomputeFrames(node.name);
			context.setNode(bundle, initialClassState, node);
		}
	}

	/**
	 * Matches: NEW SB, DUP, INVOKESPECIAL <init>()V, (LDC str, INVOKEVIRTUAL append(String))*, INVOKEVIRTUAL toString.
	 * Also accepts the {@code <init>(String)} constructor form. Returns every matched instruction in order.
	 */
	private static List<AbstractInsnNode> matchChain(AbstractInsnNode start) {
		List<AbstractInsnNode> chain = new ArrayList<>();
		chain.add(start);
		AbstractInsnNode cur = realNext(start);
		if (cur == null || cur.getOpcode() != Opcodes.DUP) return null;
		chain.add(cur);
		cur = realNext(cur);
		if (cur instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
			chain.add(cur);
			cur = realNext(cur);
			if (!(cur instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL || !SB.equals(init.owner)
					|| !init.name.equals("<init>") || !init.desc.equals("(Ljava/lang/String;)V")) return null;
			chain.add(cur);
		} else if (cur instanceof MethodInsnNode init && init.getOpcode() == Opcodes.INVOKESPECIAL && SB.equals(init.owner)
				&& init.name.equals("<init>") && init.desc.equals("()V")) {
			chain.add(cur);
		} else {
			return null;
		}
		int appends = 0;
		while (true) {
			cur = realNext(cur);
			if (cur instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
				AbstractInsnNode after = realNext(cur);
				if (after instanceof MethodInsnNode min && min.getOpcode() == Opcodes.INVOKEVIRTUAL && SB.equals(min.owner)
						&& min.name.equals("append") && min.desc.equals("(Ljava/lang/String;)Ljava/lang/StringBuilder;")) {
					chain.add(cur);
					chain.add(after);
					appends++;
					cur = after;
					continue;
				}
				return null;
			}
			if (cur instanceof MethodInsnNode min && min.getOpcode() == Opcodes.INVOKEVIRTUAL && SB.equals(min.owner)
					&& min.name.equals("toString") && min.desc.equals("()Ljava/lang/String;")) {
				chain.add(cur);
				// Only fold when there is at least one string involved, otherwise leave `new StringBuilder().toString()` alone.
				boolean hasString = chain.stream().anyMatch(c -> c instanceof LdcInsnNode);
				return hasString || appends > 0 ? chain : null;
			}
			return null;
		}
	}

	private static AbstractInsnNode realNext(AbstractInsnNode insn) {
		AbstractInsnNode cur = insn.getNext();
		// A label can be a branch target or exception boundary. Never fold across one.
		while (cur instanceof org.objectweb.asm.tree.LineNumberNode) cur = cur.getNext();
		if (cur != null && cur.getOpcode() < 0) return null;
		return cur;
	}

	@Nonnull
	@Override
	public Set<Class<? extends ClassTransformer>> recommendedPredecessors() {
		return Set.of(CallResultInliningTransformer.class, OpaqueConstantFoldingTransformer.class);
	}

	@Nonnull
	@Override
	public String identifier() {
		return IDENTIFIER;
	}
}
