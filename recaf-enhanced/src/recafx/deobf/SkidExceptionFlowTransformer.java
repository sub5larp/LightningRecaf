package recafx.deobf;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.Set;

/** Turns a proven throw-null/catch-discard edge into a direct jump, preserving the remaining handler. */
public final class SkidExceptionFlowTransformer extends LocalRewriteTransformer implements Opcodes {
    @Override public String identifier() { return "lightning.skid.exception-edges"; }
    @Override public int rewrite(ClassNode node) {
        int changes=0;
        for(MethodNode method:node.methods) {
            if(method.tryCatchBlocks.isEmpty() || method.instructions.size()>12000) continue;
            Frame<BasicValue>[] frames;
            try { frames=new Analyzer<>(new BasicInterpreter()).analyze(node.name,method); }
            catch(Exception failure) { continue; }
            AbstractInsnNode[] instructions=method.instructions.toArray();
            for(int i=0;i<instructions.length;i++) {
                AbstractInsnNode value=instructions[i];
                if(value.getOpcode()!=ACONST_NULL || frames[i]==null || frames[i].getStackSize()!=0) continue;
                AbstractInsnNode throwing=value.getNext();
                if(throwing==null || throwing.getOpcode()!=ATHROW) continue;
                int index=method.instructions.indexOf(throwing);
                TryCatchBlockNode chosen=null;
                // Exception-table ordering matters: never skip an earlier possibly matching handler.
                for(TryCatchBlockNode block:method.tryCatchBlocks) {
                    if(index>=method.instructions.indexOf(block.start) && index<method.instructions.indexOf(block.end)) {
                        chosen=block;break;
                    }
                }
                if(chosen==null || chosen.type!=null && !Set.of("java/lang/NullPointerException","java/lang/RuntimeException","java/lang/Exception","java/lang/Throwable").contains(chosen.type)) continue;
                AbstractInsnNode pop=chosen.handler;
                while(pop!=null && pop.getOpcode()<0)pop=pop.getNext();
                if(pop==null || pop.getOpcode()!=POP)continue;
                LabelNode destination=new LabelNode();
                method.instructions.insert(pop,destination);
                method.instructions.set(value,new JumpInsnNode(GOTO,destination));
                method.instructions.remove(throwing);changes++;
            }
        }
        return changes;
    }
}
