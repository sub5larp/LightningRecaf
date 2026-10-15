package recafx.deobf;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
/** Identities used in opaque integer/long predicates. No float rules or division assumptions. */
public final class SkidOpaqueMathTransformer extends LocalRewriteTransformer implements Opcodes {
    @Override public String identifier() { return "lightning.skid.opaque-identities"; }
    @Override public int rewrite(ClassNode node) {
        int changes=0;
        for(MethodNode method:node.methods) for(AbstractInsnNode insn:method.instructions.toArray()) {
            if (!(insn instanceof VarInsnNode first) || first.getOpcode()!=ILOAD && first.getOpcode()!=LLOAD) continue;
            if (!(first.getNext() instanceof VarInsnNode second) || second.var!=first.var || second.getOpcode()!=first.getOpcode()) continue;
            AbstractInsnNode operation=second.getNext(); if(operation==null) continue;
            boolean wide=first.getOpcode()==LLOAD;
            int op=operation.getOpcode();
            if(op==(wide?LXOR:IXOR) || op==(wide?LSUB:ISUB)) {
                method.instructions.set(first,new InsnNode(wide?LCONST_0:ICONST_0));
                method.instructions.remove(second);method.instructions.remove(operation);changes++;
            } else if(op==(wide?LAND:IAND) || op==(wide?LOR:IOR)) {
                method.instructions.remove(second);method.instructions.remove(operation);changes++;
            }
        }
        return changes;
    }
}
