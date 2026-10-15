package recafx.deobf;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import software.coley.recaf.util.analysis.ReInterpreter;
import software.coley.recaf.util.analysis.value.*;
import java.util.*;

/** Monotone constant propagation over executable edges. Unknown conditions retain both
 * successors; changing loop values widen through the interpreter's normal value merge. */
final class SeedFrameAnalyzer implements Opcodes {
    private SeedFrameAnalyzer() {}
    @SuppressWarnings("unchecked")
    static Frame<ReValue>[] analyze(String owner,MethodNode method,ReInterpreter interpreter)throws AnalyzerException {
        var baseline=analyze(owner,method,interpreter,-1);
        int partitionLocal=partitionLocal(method);
        if(partitionLocal<0)return baseline;
        boolean ambiguous=false;
        var code=method.instructions.toArray();
        for(int i=0;i<code.length;i++)if(baseline[i]!=null&&code[i] instanceof VarInsnNode load&&load.getOpcode()==ILOAD&&load.var==partitionLocal
                &&integer(baseline[i].getLocal(partitionLocal))==null){ambiguous=true;break;}
        if(!ambiguous)return baseline;
        try {
            var refined=analyze(owner,method,interpreter,partitionLocal);
            // A bounded second analysis can widen earlier or exhaust its call-model
            // budget. Keep every fact already established by the ordinary analysis.
            // Supplement only exact primitive facts, which hold on all refined paths.
            var result=(Frame<ReValue>[])new Frame<?>[baseline.length];
            for(int i=0;i<baseline.length;i++)if(baseline[i]!=null) {
                result[i]=new Frame<>(baseline[i]);var more=refined[i];if(more==null)continue;
                if(more.getLocals()!=result[i].getLocals()||more.getStackSize()!=result[i].getStackSize())return baseline;
                for(int j=0;j<more.getLocals();j++)result[i].setLocal(j,refine(result[i].getLocal(j),more.getLocal(j)));
                for(int j=0;j<more.getStackSize();j++)result[i].setStack(j,refine(result[i].getStack(j),more.getStack(j)));
            }
            return result;
        }catch(AnalyzerException|IllegalArgumentException exhausted){return baseline;}
    }
    private static ReValue refine(ReValue before,ReValue after) {
        if(before instanceof IntValue a&&after instanceof IntValue b&&b.hasKnownValue()) {
            if(a.hasKnownValue()&&a.value().getAsInt()!=b.value().getAsInt())throw new IllegalArgumentException("Conflicting integer facts");
            return b;
        }
        if(before instanceof LongValue a&&after instanceof LongValue b&&b.hasKnownValue()) {
            if(a.hasKnownValue()&&a.value().getAsLong()!=b.value().getAsLong())throw new IllegalArgumentException("Conflicting long facts");
            return b;
        }
        return before;
    }
    /** Retain a small disjunction for heavily updated integer seed locals. The
     * choice is only a precision heuristic: every incoming state is retained or
     * widened, never selected because it looks like an obfuscator's intended path. */
    private static int partitionLocal(MethodNode method) {
        if(method.maxLocals>128||method.instructions.size()>6000)return -1;
        int[] scores=new int[method.maxLocals];
        for(var insn:method.instructions)if(insn instanceof VarInsnNode store&&store.getOpcode()==ISTORE) {
            var previous=store.getPrevious();
            if(previous!=null&&previous.getOpcode()==IXOR&&store.var<scores.length)scores[store.var]++;
        }
        int winner=-1,score=3;
        for(int i=0;i<scores.length;i++)if(scores[i]>score){winner=i;score=scores[i];}
        return winner;
    }
    @SuppressWarnings("unchecked")
    private static Frame<ReValue>[] analyze(String owner,MethodNode method,ReInterpreter interpreter,int partitionLocal)throws AnalyzerException {
        AbstractInsnNode[] code=method.instructions.toArray();
        if(code.length==0)return (Frame<ReValue>[])new Frame<?>[0];
        var positions=new IdentityHashMap<AbstractInsnNode,Integer>();for(int i=0;i<code.length;i++)positions.put(code[i],i);
        var states=new States(code,partitionLocal,interpreter);
        Frame<ReValue> entry=new Frame<>(method.maxLocals,method.maxStack);
        entry.setReturn(interpreter.newValue(Type.getReturnType(method.desc)));
        for(int i=0;i<method.maxLocals;i++)entry.setLocal(i,interpreter.newValue(null));
        int local=0;if((method.access&ACC_STATIC)==0)entry.setLocal(local++,interpreter.newValue(Type.getObjectType(owner)));
        for(Type type:Type.getArgumentTypes(method.desc)){entry.setLocal(local++,interpreter.newValue(type));if(type.getSize()==2)entry.setLocal(local++,interpreter.newValue(null));}
        states.merge(0,entry);int steps=0;
        while(!states.queue.isEmpty()) {
            int index=states.queue.removeFirst();states.queued[index]=false;
            var snapshot=states.snapshot(index);
            for(var before:snapshot) {
            steps++;
            if(steps>100_000||Thread.currentThread().isInterrupted())throw new AnalyzerException(null,"Seed propagation budget exceeded");
            var insn=code[index];int op=insn.getOpcode();var after=new Frame<>(before);
            if(op==JSR || op==RET)throw new AnalyzerException(insn,"Legacy subroutine retained");
            if(op>=0)after.execute(insn,interpreter);
            if(insn instanceof JumpInsnNode jump) {
                Boolean taken=condition(op,before);
                if(op==GOTO || taken==null || taken)states.merge(positions.get(jump.label),after);
                if(op!=GOTO && (taken==null || !taken))states.merge(index+1,after);
            } else if(insn instanceof LookupSwitchInsnNode sw) {
                Integer key=integer(before.getStack(before.getStackSize()-1));
                if(key!=null){int at=sw.keys.indexOf(key);states.merge(positions.get(at<0?sw.dflt:sw.labels.get(at)),after);}
                else {states.merge(positions.get(sw.dflt),after);for(var label:sw.labels)states.merge(positions.get(label),after);}
            } else if(insn instanceof TableSwitchInsnNode sw) {
                Integer key=integer(before.getStack(before.getStackSize()-1));
                if(key!=null)states.merge(positions.get(key<sw.min || key>sw.max?sw.dflt:sw.labels.get(key-sw.min)),after);
                else {states.merge(positions.get(sw.dflt),after);for(var label:sw.labels)states.merge(positions.get(label),after);}
            } else if(!(op>=IRETURN && op<=RETURN) && op!=ATHROW)states.merge(index+1,after);
            // Integer/local bookkeeping cannot throw. Including those instructions
            // invents exceptional seed states that Skid's handlers can never receive.
            // Every instruction that may throw still contributes to every covering handler.
            if(mayThrow(insn))for(var handler:method.tryCatchBlocks)if(index>=positions.get(handler.start) && index<positions.get(handler.end)) {
                var exceptional=new Frame<>(before);exceptional.clearStack();exceptional.push(interpreter.newValue(Type.getObjectType(handler.type==null?"java/lang/Throwable":handler.type)));
                states.merge(positions.get(handler.handler),exceptional);
            }
            }
        }
        return states.joined();
    }
    private static boolean mayThrow(AbstractInsnNode insn) {
        int op=insn.getOpcode();if(op<0)return false;
        if(insn instanceof LdcInsnNode ldc)return !(ldc.cst instanceof Number);
        return switch(op) {
            case NOP,ACONST_NULL,ICONST_M1,ICONST_0,ICONST_1,ICONST_2,ICONST_3,ICONST_4,ICONST_5,LCONST_0,LCONST_1,
                 FCONST_0,FCONST_1,FCONST_2,DCONST_0,DCONST_1,BIPUSH,SIPUSH,
                 ILOAD,LLOAD,FLOAD,DLOAD,ALOAD,ISTORE,LSTORE,FSTORE,DSTORE,ASTORE,
                 POP,POP2,DUP,DUP_X1,DUP_X2,DUP2,DUP2_X1,DUP2_X2,SWAP,
                 IADD,LADD,FADD,DADD,ISUB,LSUB,FSUB,DSUB,IMUL,LMUL,FMUL,DMUL,FDIV,DDIV,FREM,DREM,
                 INEG,LNEG,FNEG,DNEG,ISHL,LSHL,ISHR,LSHR,IUSHR,LUSHR,IAND,LAND,IOR,LOR,IXOR,LXOR,IINC,
                 I2L,I2F,I2D,L2I,L2F,L2D,F2I,F2L,F2D,D2I,D2L,D2F,I2B,I2C,I2S,LCMP,FCMPL,FCMPG,DCMPL,DCMPG,
                 IFEQ,IFNE,IFLT,IFGE,IFGT,IFLE,IF_ICMPEQ,IF_ICMPNE,IF_ICMPLT,IF_ICMPGE,IF_ICMPGT,IF_ICMPLE,
                 IF_ACMPEQ,IF_ACMPNE,GOTO,TABLESWITCH,LOOKUPSWITCH,IFNULL,IFNONNULL,
                 IRETURN,LRETURN,FRETURN,DRETURN,ARETURN,RETURN -> false;
            default -> true;
        };
    }
    private static final class States {
        final Map<Integer,Frame<ReValue>>[] frames;
        final Frame<ReValue>[] single;
        final boolean[] queued,widened;
        final boolean[] incomingStore;
        final Deque<Integer> queue=new ArrayDeque<>();
        final int local;final ReInterpreter interpreter;
        int allocations,edges;
        @SuppressWarnings("unchecked")
        States(AbstractInsnNode[] code,int local,ReInterpreter interpreter) {
            int count=code.length;
            frames=local<0?null:(Map<Integer,Frame<ReValue>>[])new Map<?,?>[count];single=local<0?(Frame<ReValue>[])new Frame<?>[count]:null;
            queued=new boolean[count];widened=new boolean[count];this.local=local;this.interpreter=interpreter;
            incomingStore=new boolean[count];boolean nextStore=false;
            for(int i=count-1;i>=0;i--) {
                if(code[i].getOpcode()>=0)nextStore=code[i] instanceof VarInsnNode store&&store.getOpcode()==ISTORE&&store.var==local;
                incomingStore[i]=nextStore;
            }
        }
        List<Frame<ReValue>> snapshot(int index) {
            if(single!=null)return List.of(new Frame<>(single[index]));
            var out=new ArrayList<Frame<ReValue>>();for(var frame:frames[index].values())out.add(new Frame<>(frame));return out;
        }
        void merge(int index,Frame<ReValue> incoming)throws AnalyzerException {
            if(++edges>2_000_000)throw new AnalyzerException(null,"Seed edge budget");
            if(index>=queued.length)return;
            if(single!=null) {
                boolean changed;
                if(single[index]==null){single[index]=new Frame<>(incoming);changed=true;}else changed=single[index].merge(incoming,interpreter);
                if(changed&&!queued[index]){queue.add(index);queued[index]=true;}
                return;
            }
            var partitions=frames[index];if(partitions==null)frames[index]=partitions=new LinkedHashMap<>();
            // javac may join a conditional's stack values immediately before
            // ISTORE. Partition that incoming assignment value through metadata
            // nodes as well, so the disjunction is not lost one instruction early.
            Integer key=incomingStore[index]&&incoming.getStackSize()>0
                    ?integer(incoming.getStack(incoming.getStackSize()-1)):integer(incoming.getLocal(local));
            boolean changed=false;
            if(!widened[index]&&(key==null||!partitions.containsKey(key)&&partitions.size()>=8)) {
                var joined=new Frame<>(incoming);for(var old:partitions.values())joined.merge(old,interpreter);
                partitions.clear();partitions.put(null,joined);widened[index]=true;changed=true;
            } else {
                if(widened[index])key=null;
                var current=partitions.get(key);
                if(current==null) {
                    if(++allocations>40_000)throw new AnalyzerException(null,"Seed partition storage budget");
                    partitions.put(key,new Frame<>(incoming));changed=true;
                }else changed=current.merge(incoming,interpreter);
            }
            if(changed&&!queued[index]){queue.add(index);queued[index]=true;}
        }
        @SuppressWarnings("unchecked")
        Frame<ReValue>[] joined()throws AnalyzerException {
            if(single!=null)return single;
            var result=(Frame<ReValue>[])new Frame<?>[frames.length];
            for(int i=0;i<frames.length;i++)if(frames[i]!=null)for(var frame:frames[i].values()) {
                if(result[i]==null)result[i]=new Frame<>(frame);else result[i].merge(frame,interpreter);
            }
            return result;
        }
    }
    private static Integer integer(ReValue value){return value instanceof IntValue number && number.value().isPresent()?number.value().getAsInt():null;}
    private static Boolean condition(int op,Frame<ReValue> frame) {
        if(op==GOTO)return true;
        if(op>=IFEQ && op<=IFLE) {
            Integer value=integer(frame.getStack(frame.getStackSize()-1));if(value==null)return null;
            return switch(op){case IFEQ->value==0;case IFNE->value!=0;case IFLT->value<0;case IFGE->value>=0;case IFGT->value>0;default->value<=0;};
        }
        if(op>=IF_ICMPEQ && op<=IF_ICMPLE) {
            Integer right=integer(frame.getStack(frame.getStackSize()-1)),left=integer(frame.getStack(frame.getStackSize()-2));if(right==null || left==null)return null;
            return switch(op){case IF_ICMPEQ->left.intValue()==right.intValue();case IF_ICMPNE->left.intValue()!=right.intValue();case IF_ICMPLT->left<right;case IF_ICMPGE->left>=right;case IF_ICMPGT->left>right;default->left<=right;};
        }
        return null;
    }
}
