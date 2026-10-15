package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Exact structural recognition of the single-key ZKM reference adapter. Names may
 * change; instructions, operands, catch ranges and destinations may not. */
public final class ZkmBootstrapShape implements Opcodes {
    public static final String PREFIX="(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/String;Ljava/lang/invoke/MethodType;";
    public static final String BOOTSTRAP="(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";
    public static final String ADAPTER=PREFIX+"[Ljava/lang/Object;)Ljava/lang/Object;";
    public static final String RESOLVER=PREFIX+"J)Ljava/lang/invoke/MethodHandle;";
    private static final String MCS="java/lang/invoke/MutableCallSite", MH="java/lang/invoke/MethodHandle", MHS="java/lang/invoke/MethodHandles", MT="java/lang/invoke/MethodType";
    private ZkmBootstrapShape(){}
    public static MethodNode resolver(ClassNode owner,Handle bootstrap) {
        if(bootstrap.getTag()!=H_INVOKESTATIC || bootstrap.isInterface() || !bootstrap.getOwner().equals(owner.name) || !bootstrap.getDesc().equals(BOOTSTRAP))return null;
        MethodNode boot=find(owner,bootstrap.getName(),BOOTSTRAP);if(boot==null)return null;
        Handle handle=null;
        for(var insn:boot.instructions)if(insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle found){if(handle!=null)return null;handle=found;}
        if(handle==null || handle.getTag()!=H_INVOKESTATIC || handle.isInterface() || !handle.getOwner().equals(owner.name) || !handle.getDesc().equals(ADAPTER))return null;
        MethodNode spread=find(owner,handle.getName(),ADAPTER);if(spread==null)return null;
        MethodInsnNode call=null;
        for(var insn:spread.instructions)if(insn instanceof MethodInsnNode found && found.owner.equals(owner.name)){if(call!=null)return null;call=found;}
        if(call==null || call.getOpcode()!=INVOKESTATIC || !call.desc.equals(RESOLVER))return null;
        MethodNode resolver=find(owner,call.name,RESOLVER);
        return resolver!=null && same(boot,bootstrap(owner.name,handle.getName())) && same(spread,adapter(owner.name,call.name))?resolver:null;
    }
    private static MethodNode find(ClassNode owner,String name,String desc){return owner.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)&&(m.access&(ACC_STATIC|ACC_ABSTRACT|ACC_NATIVE|ACC_SYNCHRONIZED))==ACC_STATIC).findFirst().orElse(null);}
    private static boolean same(MethodNode actual,MethodNode expected) {
        if(actual.tryCatchBlocks.size()!=expected.tryCatchBlocks.size())return false;
        if(!code(actual).equals(code(expected)))return false;
        for(int i=0;i<actual.tryCatchBlocks.size();i++) {
            var a=actual.tryCatchBlocks.get(i);var b=expected.tryCatchBlocks.get(i);
            if(!Objects.equals(a.type,b.type)||position(actual,a.start)!=position(expected,b.start)||position(actual,a.end)!=position(expected,b.end)||position(actual,a.handler)!=position(expected,b.handler))return false;
        }
        return true;
    }
    private static int position(MethodNode method,AbstractInsnNode target){int index=0;for(var insn:method.instructions){if(insn==target)return index;if(insn.getOpcode()>=0)index++;}return -1;}
    private static List<String> code(MethodNode method) {
        var result=new ArrayList<String>();
        for(var insn:method.instructions)if(insn.getOpcode()>=0) {
            String operand=switch(insn) {
                case VarInsnNode v->"v"+v.var;
                case TypeInsnNode t->t.desc;
                case MethodInsnNode c->c.owner+'.'+c.name+c.desc+":"+c.itf;
                case LdcInsnNode l->l.cst.getClass().getName()+":"+l.cst;
                case JumpInsnNode j->"j"+position(method,j.label);
                case InsnNode ignored->"";
                default->"unsupported"+System.identityHashCode(insn);
            };
            result.add(insn.getOpcode()+":"+operand);
        }
        return result;
    }
    /** Templates are authored from the public JVM API operations, not an unchecked byte hash. */
    public static MethodNode bootstrap(String owner,String adapter) {
        var m=new MethodNode(ACC_PUBLIC|ACC_STATIC,"bootstrap",BOOTSTRAP,null,null);
        Label start=new Label(),end=new Label(),failure=new Label(),done=new Label();
        m.visitTypeInsn(NEW,MCS);m.visitInsn(DUP);m.visitVarInsn(ALOAD,2);call(m,INVOKESPECIAL,MCS,"<init>","(Ljava/lang/invoke/MethodType;)V");m.visitVarInsn(ASTORE,3);
        m.visitLabel(start);m.visitVarInsn(ALOAD,3);m.visitLdcInsn(new Handle(H_INVOKESTATIC,owner,adapter,ADAPTER,false));m.visitLdcInsn(Type.getType("[Ljava/lang/Object;"));m.visitVarInsn(ALOAD,2);
        call(m,INVOKEVIRTUAL,MT,"parameterCount","()I");call(m,INVOKEVIRTUAL,MH,"asCollector","(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;");
        m.visitInsn(ICONST_0);m.visitInsn(ICONST_4);m.visitTypeInsn(ANEWARRAY,"java/lang/Object");
        int[] locals={0,3,1,2};for(int i=0;i<4;i++){m.visitInsn(DUP);m.visitInsn(ICONST_0+i);m.visitVarInsn(ALOAD,locals[i]);m.visitInsn(AASTORE);}
        call(m,INVOKESTATIC,MHS,"insertArguments","(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;");m.visitVarInsn(ALOAD,2);
        call(m,INVOKESTATIC,MHS,"explicitCastArguments","(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");call(m,INVOKEVIRTUAL,MCS,"setTarget","(Ljava/lang/invoke/MethodHandle;)V");
        m.visitLabel(end);m.visitJumpInsn(GOTO,done);m.visitLabel(failure);m.visitVarInsn(ASTORE,4);m.visitTypeInsn(NEW,"java/lang/RuntimeException");m.visitInsn(DUP);m.visitTypeInsn(NEW,"java/lang/StringBuilder");m.visitInsn(DUP);call(m,INVOKESPECIAL,"java/lang/StringBuilder","<init>","()V");
        m.visitLdcInsn(owner);append(m);m.visitLdcInsn(" : ");append(m);m.visitVarInsn(ALOAD,1);append(m);m.visitLdcInsn(" : ");append(m);m.visitVarInsn(ALOAD,2);call(m,INVOKEVIRTUAL,MT,"toString","()Ljava/lang/String;");append(m);
        call(m,INVOKEVIRTUAL,"java/lang/StringBuilder","toString","()Ljava/lang/String;");m.visitVarInsn(ALOAD,4);call(m,INVOKESPECIAL,"java/lang/RuntimeException","<init>","(Ljava/lang/String;Ljava/lang/Throwable;)V");m.visitInsn(ATHROW);m.visitLabel(done);m.visitVarInsn(ALOAD,3);m.visitInsn(ARETURN);
        m.visitTryCatchBlock(start,end,failure,"java/lang/Exception");m.visitMaxs(10,5);return m;
    }
    public static MethodNode adapter(String owner,String resolver) {
        var m=new MethodNode(ACC_PUBLIC|ACC_STATIC,"adapter",ADAPTER,null,null);
        m.visitVarInsn(ALOAD,4);m.visitInsn(ARRAYLENGTH);m.visitInsn(ICONST_1);m.visitInsn(ISUB);m.visitVarInsn(ISTORE,5);m.visitVarInsn(ALOAD,4);m.visitVarInsn(ILOAD,5);m.visitInsn(AALOAD);m.visitTypeInsn(CHECKCAST,"java/lang/Long");call(m,INVOKEVIRTUAL,"java/lang/Long","longValue","()J");m.visitVarInsn(LSTORE,6);
        for(int i=0;i<4;i++)m.visitVarInsn(ALOAD,i);m.visitVarInsn(LLOAD,6);call(m,INVOKESTATIC,owner,resolver,RESOLVER);m.visitVarInsn(ASTORE,8);m.visitVarInsn(ALOAD,1);m.visitVarInsn(ALOAD,8);m.visitVarInsn(ALOAD,3);
        call(m,INVOKESTATIC,MHS,"explicitCastArguments","(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");call(m,INVOKEVIRTUAL,MCS,"setTarget","(Ljava/lang/invoke/MethodHandle;)V");
        m.visitVarInsn(ALOAD,8);m.visitLdcInsn(Type.getType("[Ljava/lang/Object;"));m.visitVarInsn(ALOAD,4);m.visitInsn(ARRAYLENGTH);call(m,INVOKEVIRTUAL,MH,"asSpreader","(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;");m.visitVarInsn(ALOAD,4);call(m,INVOKEVIRTUAL,MH,"invoke","([Ljava/lang/Object;)Ljava/lang/Object;");m.visitInsn(ARETURN);m.visitMaxs(8,9);return m;
    }
    private static void call(MethodNode m,int opcode,String owner,String name,String desc){m.visitMethodInsn(opcode,owner,name,desc,false);}
    private static void append(MethodNode m){call(m,INVOKEVIRTUAL,"java/lang/StringBuilder","append","(Ljava/lang/String;)Ljava/lang/StringBuilder;");}
}
