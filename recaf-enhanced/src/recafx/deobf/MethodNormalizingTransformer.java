package recafx.deobf;

import jakarta.annotation.Nonnull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

/** Local CFG cleanup with no obfuscator-name guesses or member deletion. */
public final class MethodNormalizingTransformer implements JvmClassTransformer {
    public static final String IDENTIFIER = "lightning.normalize.methods";
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
        int changes = 0;
        for (int index = 0; index < node.methods.size(); index++) {
            MethodNode method = node.methods.get(index);
            boolean subroutine = false;
            for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() == Opcodes.JSR) subroutine = true;
            if (subroutine) {
                MethodNode replacement = new MethodNode(Opcodes.ASM9, method.access, method.name, method.desc,
                        method.signature, method.exceptions.toArray(String[]::new));
                method.accept(new JSRInlinerAdapter(replacement, method.access, method.name, method.desc,
                        method.signature, method.exceptions.toArray(String[]::new)));
                node.methods.set(index, replacement);
                method = replacement;
                changes++;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.NOP) { method.instructions.remove(insn); changes++; }
                else if (insn instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) {
                    AbstractInsnNode next = insn.getNext();
                    while (next != null && next != jump.label && next.getOpcode() < 0) next = next.getNext();
                    if (next == jump.label) { method.instructions.remove(insn); changes++; }
                } else {
                    LabelNode destination = null;
                    if (insn instanceof LookupSwitchInsnNode sw && sw.labels.stream().allMatch(l -> l == sw.dflt)) destination = sw.dflt;
                    if (insn instanceof TableSwitchInsnNode sw && sw.labels.stream().allMatch(l -> l == sw.dflt)) destination = sw.dflt;
                    if (destination != null) {
                        method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                        method.instructions.set(insn, new JumpInsnNode(Opcodes.GOTO, destination));
                        changes++;
                    }
                }
            }
        }
        return changes;
    }
}
