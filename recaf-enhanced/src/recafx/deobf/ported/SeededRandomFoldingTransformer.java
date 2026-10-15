/* SPDX-License-Identifier: GPL-3.0-or-later
 * Adapted from Diobf/Deobfuscator SkidNumberTransformer (uwu.narumi contributors).
 * LightningRecaf adaptation: exact owners/descriptors, control-flow boundaries,
 * boolean JVM constants, preservation of helpers, and no target class execution.
 * Source and full license: THIRD-PARTY-NOTICES.txt and licenses/Diobfuscator-GPL-3.0.txt.
 */
package recafx.deobf.ported;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import java.util.Random;

/** Skidfuscator's deterministic fresh Random(seed).next* constant encoding. */
public final class SeededRandomFoldingTransformer implements JvmClassTransformer {
    public static final String IDENTIFIER = "lightning.skidfuscator.seeded-random";
    @Override public String identifier() { return IDENTIFIER; }
    @Override public void transform(@Nonnull JvmTransformerContext context, @Nonnull Workspace workspace,
            @Nonnull WorkspaceResource resource, @Nonnull JvmClassBundle bundle, @Nonnull JvmClassInfo info) {
        ClassNode node = context.getNode(bundle, info);
        if (rewrite(node) > 0) {
            context.setRecomputeFrames(node.name);
            context.setNode(bundle, info, node);
        }
    }
    public static int rewrite(ClassNode node) {
        int changed = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode first : method.instructions.toArray()) {
                if (!(first instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
                        || !type.desc.equals("java/util/Random")) continue;
                AbstractInsnNode dup = next(first), seed = next(dup), ctor = next(seed), call = next(ctor);
                if (dup == null || dup.getOpcode() != Opcodes.DUP || seed == null
                        || !PortedUtil.isLongConst(seed)
                        || !(ctor instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL
                        || !init.owner.equals(type.desc) || !init.name.equals("<init>") || !init.desc.equals("(J)V")
                        || !(call instanceof MethodInsnNode invoke) || invoke.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !invoke.owner.equals(type.desc) || invoke.itf) continue;
                Random random = new Random(PortedUtil.longValue(seed));
                Object value = switch (invoke.name + invoke.desc) {
                    case "nextInt()I" -> random.nextInt();
                    case "nextLong()J" -> random.nextLong();
                    case "nextBoolean()Z" -> random.nextBoolean() ? 1 : 0;
                    case "nextFloat()F" -> random.nextFloat();
                    case "nextDouble()D" -> random.nextDouble();
                    default -> null;
                };
                if (value == null) continue;
                method.instructions.insertBefore(first, new LdcInsnNode(value));
                for (AbstractInsnNode insn : new AbstractInsnNode[]{first, dup, seed, ctor, call}) method.instructions.remove(insn);
                changed++;
            }
        }
        return changed;
    }
    private static AbstractInsnNode next(AbstractInsnNode insn) {
        if (insn == null) return null;
        AbstractInsnNode next = insn.getNext();
        while (next instanceof LineNumberNode) next = next.getNext();
        return next != null && next.getOpcode() >= 0 ? next : null;
    }
}
