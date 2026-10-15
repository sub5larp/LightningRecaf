package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.util.analysis.*;
import software.coley.recaf.util.analysis.eval.*;
import software.coley.recaf.util.analysis.lookup.InvokeStaticLookup;
import software.coley.recaf.util.analysis.value.*;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import java.util.*;
import static software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer.toInsn;

/** Constant seed propagation and branch/switch folding with explicitly audited call models.
 * Unknown calls remain unknown; their original instructions and side effects are retained. */
public final class GuardedFlowTransformer implements JvmClassTransformer, Opcodes {
    private final InheritanceGraphService graphs;
    public GuardedFlowTransformer(InheritanceGraphService graphs){this.graphs=graphs;}
    @Override public String identifier(){return "lightning.flow.constant-seeds";}
    @Override public Set<Class<? extends ClassTransformer>> dependencies(){return Set.of(GuardedStaticValueCollector.class);}
    @Override public void transform(JvmTransformerContext context,Workspace workspace,WorkspaceResource resource,JvmClassBundle bundle,JvmClassInfo info) throws TransformationException {
        var owner=context.getNode(bundle,info);var graph=graphs.getOrCreateInheritanceGraph(workspace);
        var constants=context.getTransformer(GuardedStaticValueCollector.class);
        Set<String> initializing=GuardedStaticValueInliner.collectInitializationMethods(owner);
        Map<CallKey,ReValue> evaluated=new HashMap<>();Map<String,Boolean> audits=new HashMap<>();int[] attempts={0};
        boolean dirty=false;
        for(var method:owner.methods) {
            if((method.access&(ACC_ABSTRACT|ACC_NATIVE))!=0 || method.instructions.size()==0 || method.instructions.size()>12000)continue;
            // No finalized field assumptions in code that can run during initialization.
            boolean useFields=!initializing.contains(method.name+method.desc);
            for(int round=0;round<4;round++) {
                var jdk=context.newInterpreter(graph).getInvokeStaticLookup();
                var interpreter=new ReInterpreter(graph) {
                    @Override public ReValue binaryOperation(AbstractInsnNode insn,ReValue left,ReValue right) {
                        // Local arrays can escape into an unknown call. Their elements are heap state,
                        // not immutable seed facts; dedicated array passes own those stronger proofs.
                        return switch(insn.getOpcode()) {
                            case IALOAD,BALOAD,CALOAD,SALOAD -> IntValue.UNKNOWN;
                            case LALOAD -> LongValue.UNKNOWN;
                            case FALOAD -> FloatValue.UNKNOWN;
                            case DALOAD -> DoubleValue.UNKNOWN;
                            case AALOAD -> ObjectValue.VAL_OBJECT_MAYBE_NULL;
                            default -> super.binaryOperation(insn,left,right);
                        };
                    }
                };
                interpreter.setGetFieldLookup(null);interpreter.setInvokeVirtualLookup(null);
                interpreter.setGetStaticLookup(useFields?constants:null);
                interpreter.setInvokeStaticLookup(new InvokeStaticLookup() {
                    @Override public boolean hasLookup(MethodInsnNode call) {
                        if(!EvaluationPolicy.immutableArgument(Type.getReturnType(call.desc)) || Type.getReturnType(call.desc).getSort()==Type.VOID
                                || !Arrays.stream(Type.getArgumentTypes(call.desc)).allMatch(EvaluationPolicy::immutableArgument))return false;
                        if(EvaluationPolicy.jdkCall(call))return jdk!=null && jdk.hasLookup(call);
                        return audits.computeIfAbsent(call.owner+'.'+call.name+call.desc+':'+useFields,key->{
                            var target=context.resolveMethod(call);
                            if(target==null || (!call.owner.equals(owner.name) && constants.isInitializationObservable(call.owner)))return false;
                            return useFields?EvaluationPolicy.withConstants(context,target.classNode(),target.methodNode(),constants):EvaluationPolicy.pure(context,target.classNode(),target.methodNode());
                        });
                    }
                    @Override public ReValue get(MethodInsnNode call,List<? extends ReValue> values) {
                        ReValue unknown=unknown(Type.getReturnType(call.desc));
                        try {
                            // ReInterpreter calls get() directly; hasLookup() is not an automatic guard.
                            if(!hasLookup(call))return unknown;
                            if(EvaluationPolicy.jdkCall(call))return jdk.get(call,values);
                            var key=new CallKey(call.owner,call.name,call.desc,List.copyOf(values),useFields);
                            if(evaluated.containsKey(key))return evaluated.get(key);
                            if(++attempts[0]>256)return unknown;
                            var target=context.resolveMethod(call);if(target==null)return unknown;
                            var modeled=context.newInterpreter(graph);modeled.setGetStaticLookup(useFields?constants:null);
                            var evaluator=new Evaluator(workspace,modeled,new FieldCacheManager(),8000,false,false);
                            EvaluationPolicy.installOverrides(evaluator,context,target.classNode(),target.methodNode());
                            var result=evaluator.evaluate(target.classNode(),target.methodNode(),null,new ArrayList<>(values));
                            ReValue answer=result instanceof EvaluationYieldResult yield && yield.value()!=null?yield.value():unknown;
                            evaluated.put(key,answer);return answer;
                        }catch(Exception failure){return unknown;}
                    }
                });
                Frame<ReValue>[] frames;
                try {frames=SeedFrameAnalyzer.analyze(owner.name,method,interpreter);}catch(Exception invalid){break;}
                boolean changed=false;var instructions=method.instructions.toArray();
                for(int i=0;i<instructions.length && i<frames.length;i++) {
                    var insn=instructions[i];var frame=frames[i];if(frame==null)continue;
                    if(insn instanceof VarInsnNode load && (load.getOpcode()==ILOAD || load.getOpcode()==LLOAD)) {
                        var value=frame.getLocal(load.var);var literal=toInsn(value);
                        if(literal!=null && (value instanceof IntValue || value instanceof LongValue)){method.instructions.set(insn,literal);changed=true;}
                    } else if(insn instanceof JumpInsnNode jump) {
                        int op=jump.getOpcode(),count=op>=IF_ICMPEQ && op<=IF_ICMPLE?2:op>=IFEQ && op<=IFLE?1:0;
                        if(count==0 || frame.getStackSize()<count)continue;
                        Integer right=integer(frame.getStack(frame.getStackSize()-1));
                        Integer left=count==1?right:integer(frame.getStack(frame.getStackSize()-2));
                        if(right==null || left==null)continue;if(count==1)right=0;
                        boolean taken=switch(op){case IFEQ,IF_ICMPEQ->left.intValue()==right.intValue();case IFNE,IF_ICMPNE->left.intValue()!=right.intValue();case IFLT,IF_ICMPLT->left<right;case IFGE,IF_ICMPGE->left>=right;case IFGT,IF_ICMPGT->left>right;case IFLE,IF_ICMPLE->left<=right;default->false;};
                        method.instructions.insertBefore(jump,new InsnNode(count==1?POP:POP2));
                        if(taken)method.instructions.set(jump,new JumpInsnNode(GOTO,jump.label));else method.instructions.remove(jump);
                        changed=true;
                    } else if(insn instanceof LookupSwitchInsnNode || insn instanceof TableSwitchInsnNode) {
                        if(frame.getStackSize()==0)continue;Integer key=integer(frame.getStack(frame.getStackSize()-1));if(key==null)continue;
                        LabelNode destination;
                        if(insn instanceof LookupSwitchInsnNode sw){int at=sw.keys.indexOf(key);destination=at<0?sw.dflt:sw.labels.get(at);}
                        else {var sw=(TableSwitchInsnNode)insn;destination=key<sw.min || key>sw.max?sw.dflt:sw.labels.get(key-sw.min);}
                        method.instructions.insertBefore(insn,new InsnNode(POP));method.instructions.set(insn,new JumpInsnNode(GOTO,destination));changed=true;
                    }
                }
                if(!changed)break;dirty=true;
            }
        }
        if(dirty){context.setRecomputeFrames(owner.name);context.setNode(bundle,info,owner);}
    }
    private static Integer integer(ReValue value){return value instanceof IntValue number && number.value().isPresent()?number.value().getAsInt():null;}
    private static ReValue unknown(Type type){try{return ReValue.ofType(type,Nullness.UNKNOWN);}catch(Exception invalid){return UninitializedValue.UNINITIALIZED_VALUE;}}
    private record CallKey(String owner,String name,String descriptor,List<? extends ReValue> values,boolean finalizedFields){}
}
