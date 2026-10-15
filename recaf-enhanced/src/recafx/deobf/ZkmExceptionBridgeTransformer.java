package recafx.deobf;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Removes calls to exact same-class static identity helpers immediately before a rethrow. */
public final class ZkmExceptionBridgeTransformer extends LocalRewriteTransformer implements Opcodes {
    @Override public String identifier() { return "lightning.zkm.identity-rethrows"; }
    @Override public int rewrite(ClassNode node) {
        // Calling an interface's static helper can initialize it even from a default method.
        if((node.access&ACC_INTERFACE)!=0)return 0;
        Set<String> identities=new HashSet<>();
        for(MethodNode method:node.methods) {
            if((method.access&(ACC_STATIC|ACC_NATIVE|ACC_SYNCHRONIZED))!=ACC_STATIC || !method.tryCatchBlocks.isEmpty())continue;
            Type[] args=Type.getArgumentTypes(method.desc);
            if(args.length!=1 || args[0].getSort()!=Type.OBJECT || !args[0].equals(Type.getReturnType(method.desc)))continue;
            List<AbstractInsnNode> code=new ArrayList<>();for(var insn:method.instructions)if(insn.getOpcode()>=0)code.add(insn);
            if(code.size()==2 && code.get(0) instanceof VarInsnNode v && v.getOpcode()==ALOAD && v.var==0 && code.get(1).getOpcode()==ARETURN)
                identities.add(method.name+method.desc);
        }
        int changes=0;
        for(MethodNode method:node.methods)for(AbstractInsnNode insn:method.instructions.toArray()) {
            if(!(insn instanceof MethodInsnNode call) || call.getOpcode()!=INVOKESTATIC || !call.owner.equals(node.name) || !identities.contains(call.name+call.desc))continue;
            // No crossing labels: a jump into the rethrow must retain its original stack shape.
            if(call.getNext()!=null && call.getNext().getOpcode()==ATHROW) {method.instructions.remove(call);changes++;}
        }
        return changes;
    }
}
