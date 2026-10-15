package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.util.analysis.Nullness;
import software.coley.recaf.util.analysis.eval.*;
import software.coley.recaf.util.analysis.value.*;
import software.coley.recaf.util.analysis.value.impl.ArrayValueImpl;
import software.coley.recaf.util.analysis.value.impl.ArrayValueMutableImpl;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import java.util.*;
import static software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer.toInsn;

/**
 * ZKM enhanced XOR/DES string and boxed numeric memoizers. Proves the private cache shape,
 * every direct call's constant arguments and agreement of all callers sharing a cache slot.
 * Uses the real decoder instructions; never guesses key tables or decodes arbitrary LDC strings.
 */
public abstract class ZkmMemoizedValueTransformer implements JvmClassTransformer, Opcodes {
    private final InheritanceGraphService graphs;
    protected ZkmMemoizedValueTransformer(InheritanceGraphService graphs) { this.graphs=graphs; }
    protected abstract boolean accepts(String descriptor);
    @Override public Set<Class<? extends ClassTransformer>> dependencies() { return Set.of(GuardedStaticValueCollector.class); }

    @Override public void transform(JvmTransformerContext context, Workspace workspace, WorkspaceResource resource,
                                    JvmClassBundle bundle, JvmClassInfo info) throws TransformationException {
        ClassNode owner=context.getNode(bundle,info);
        if(owner.nestHostClass!=null || owner.nestMembers!=null && !owner.nestMembers.isEmpty())return;
        var collected=context.getTransformer(GuardedStaticValueCollector.class);
        var graph=graphs.getOrCreateInheritanceGraph(workspace);
        Set<String> initialization=GuardedStaticValueInliner.collectInitializationMethods(owner);
        MethodNode clinit=owner.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElse(null);
        if(clinit==null || clinit.instructions.size()>12000)return;
        boolean dirty=false;
        for(MethodNode helper:owner.methods) {
            if((helper.access&(ACC_PRIVATE|ACC_STATIC|ACC_NATIVE|ACC_SYNCHRONIZED))!=(ACC_PRIVATE|ACC_STATIC)
                    || !accepts(helper.desc)
                    || helper.instructions.size()>12000)continue;
            FieldNode cache=findCache(owner,helper);
            if(cache==null || !closedReferences(owner,helper,cache))continue;
            Initialization initializationPlan=initialization(context,owner,helper,cache);
            if(initializationPlan==null)continue;
            GuardedStaticValueCollector constants;
            if(initializationPlan.owner==owner) constants=collected;
            else {
                constants=new GuardedStaticValueCollector(graphs);
                var prefixContext=new JvmTransformerContext(workspace,resource,constants);
                prefixContext.setNode(bundle,info,initializationPlan.owner);
                constants.setup(prefixContext,workspace);
                constants.transform(prefixContext,workspace,resource,bundle,info);
            }
            FieldCacheManager initState=new FieldCacheManager();
            try {
                for(FieldNode field:owner.fields) if((field.access&ACC_STATIC)!=0)
                    initState.getStaticFieldCache(owner.name).setField(owner.name,field.name,field.desc,
                            field.value==null ? ReValue.ofTypeDefaultValue(Type.getType(field.desc)) : ReValue.ofConstant(field.value));
            } catch(Exception invalid){continue;}
            var initEvaluator=new Evaluator(workspace,context.newInterpreter(graph),initState,300_000,false,true);
            EvaluationPolicy.installOverrides(initEvaluator,context,initializationPlan.owner,initializationPlan.clinit);
            if(!(initEvaluator.evaluateClassInitializer(initializationPlan.owner) instanceof EvaluationYieldResult))continue;
            ReValue initial=initState.getStaticFieldCache(owner.name).getField(owner.name,cache.name,cache.desc);
            if(!(initial instanceof ArrayValue initializedCache) || !initializedCache.isNotNull() || initializedCache.getFirstDimensionLength().isEmpty())continue;
            int length=initializedCache.getFirstDimensionLength().getAsInt();
            if(length<=0 || length>16384)continue;
            Object[] initialValues=new Object[length];boolean validInitial=true;
            for(int slot=0;slot<length;slot++) {
                ReValue value=initializedCache.getValue(slot);
                if(value instanceof ObjectValue object && object.isNull())continue;
                Object fixed=constantValue(value);
                if(fixed==null || !matchesReturn(helper.desc,fixed)){validInitial=false;break;}
                initialValues[slot]=fixed;
            }
            if(!validInitial)continue;

            // The policy sees this one cache as local modeled state. All other static fields must
            // still pass the immutable-field collector. Each call gets the same verified initial snapshot.
            var policyConstants=new GuardedStaticValueCollector(graphs) {
                @Override public boolean hasLookup(FieldInsnNode field) { return isCache(field,owner.name,cache) || constants.hasLookup(field); }
                @Override public ReValue get(FieldInsnNode field) {
                    if(isCache(field,owner.name,cache))return initializedCache;
                    ReValue initialized=initState.getStaticFieldCache(field.owner).getField(field.owner,field.name,field.desc);
                    return initialized!=null && constants.hasLookup(field)?initialized:constants.get(field);
                }
                @Override public boolean isInitializationObservable(String name) { return constants.isInitializationObservable(name); }
            };
            if(!EvaluationPolicy.withConstants(context,owner,helper,policyConstants))continue;
            Map<Integer,Object> slotValues=new HashMap<>();
            Map<List<ReValue>,EvaluatedCall> callResults=new HashMap<>();
            List<Replacement> replacements=new ArrayList<>();
            boolean proven=true;
            for(MethodNode method:owner.methods) {
                // Fully audited initialization is modeled intact. Never substitute its partially initialized reads.
                if(method==clinit && initializationPlan.owner==owner)continue;
                boolean calls=false;
                for(var insn:method.instructions)if(insn instanceof MethodInsnNode call && isCall(call,owner.name,helper))calls=true;
                if(!calls)continue;
                if((initialization.contains(method.name+method.desc) && method!=clinit) || method.instructions.size()>12000){proven=false;break;}
                Frame<ReValue>[] frames;
                try { var analyzer=EvaluationPolicy.frameAnalyzer(context,graph,null); frames=analyzer.analyze(owner.name,method); }
                catch(Exception failed){proven=false;break;}
                var instructions=method.instructions.toArray();
                for(int i=0;i<instructions.length;i++) {
                    if(!(instructions[i] instanceof MethodInsnNode call) || !isCall(call,owner.name,helper))continue;
                    if(method==clinit && !initializationPlan.suffix.contains(call)){proven=false;break;}
                    if(replacements.size()>=512 || frames[i]==null){proven=false;break;}
                    Type[] types=Type.getArgumentTypes(helper.desc);int count=types.length;
                    if(frames[i].getStackSize()<count){proven=false;break;}
                    List<ReValue> arguments=new ArrayList<>();
                    for(int arg=0;arg<count;arg++)arguments.add(frames[i].getStack(frames[i].getStackSize()-count+arg));
                    if(arguments.stream().anyMatch(v->v==null || !v.hasKnownValue())){proven=false;break;}
                    // Arguments are immutable primitives here. Repeated encrypted literals share a
                    // bounded evaluation within this helper and this exact initialized state only.
                    var previousCall=callResults.get(arguments);
                    if(previousCall!=null) {
                        replacements.add(new Replacement(method,call,previousCall.value));
                        continue;
                    }
                    var state=new FieldCacheManager();
                    var localCache=ArrayValueMutableImpl.wrap(new ArrayValueImpl(Type.getType(cache.desc),Nullness.NOT_NULL,length,initializedCache::getValue));
                    state.getStaticFieldCache(owner.name).setField(owner.name,cache.name,cache.desc,localCache);
                    var interpreter=context.newInterpreter(graph);interpreter.setGetStaticLookup(policyConstants);
                    var evaluator=new Evaluator(workspace,interpreter,state,100_000,false,false);
                    EvaluationPolicy.installOverrides(evaluator,context,owner,helper);
                    EvaluationResult result=evaluator.evaluate(owner,helper,null,arguments);
                    if(!(result instanceof EvaluationYieldResult yielded)) {proven=false;break;}
                    Object text=constantValue(yielded.value());
                    if(text==null || !matchesReturn(helper.desc,text)){proven=false;break;}
                    ReValue finalState=state.getStaticFieldCache(owner.name).getField(owner.name,cache.name,cache.desc);
                    if(!(finalState instanceof ArrayValue array)){proven=false;break;}
                    int changedSlot=-1;
                    for(int slot=0;slot<length;slot++) {
                        ReValue value=array.getValue(slot);
                        Object fixed=value instanceof ObjectValue object && object.isNull()?null:constantValue(value);
                        if(fixed==null && !(value instanceof ObjectValue object && object.isNull())){proven=false;break;}
                        if(Objects.equals(fixed,initialValues[slot]))continue;
                        if(initialValues[slot]!=null || changedSlot!=-1 || !Objects.equals(fixed,text)){proven=false;break;}
                        changedSlot=slot;
                    }
                    if(!proven)break;
                    if(changedSlot>=0) {
                        Object previous=slotValues.putIfAbsent(changedSlot,text);
                        if(previous!=null && !previous.equals(text)){proven=false;break;}
                    }
                    callResults.put(List.copyOf(arguments),new EvaluatedCall(text));
                    replacements.add(new Replacement(method,call,text));
                }
                if(!proven)break;
            }
            if(!proven || replacements.isEmpty())continue;
            for(Replacement replacement:replacements) {
                Type[] args=Type.getArgumentTypes(helper.desc);
                for(int arg=args.length-1;arg>=0;arg--)replacement.method.instructions.insertBefore(replacement.call,new InsnNode(args[arg].getSize()==2?POP2:POP));
                replacement.method.instructions.set(replacement.call,new LdcInsnNode(replacement.text));
            }
            dirty=true;
        }
        if(dirty){context.setRecomputeFrames(owner.name);context.setNode(bundle,info,owner);}
    }
    private record Replacement(MethodNode method,MethodInsnNode call,Object text) {}
    private record EvaluatedCall(Object value) {}
    private static Object constantValue(ReValue value) {
        if(value instanceof IntValue number && number.value().isPresent())return number.value().getAsInt();
        if(value instanceof LongValue number && number.value().isPresent())return number.value().getAsLong();
        if(value instanceof software.coley.recaf.util.analysis.value.impl.ObjectValueBoxImpl<?> box && box.hasKnownValue())return box.unbox();
        if(value instanceof InstancedObjectValue<?> instance) {
            Object raw=instance.getRealInstance();if(raw instanceof Integer || raw instanceof Long || raw instanceof String)return raw;
        }
        return toInsn(value) instanceof LdcInsnNode literal && literal.cst instanceof String ? literal.cst : null;
    }
    private static boolean matchesReturn(String descriptor,Object value) {
        return switch(Type.getReturnType(descriptor).getDescriptor()) {
            case "I" -> value instanceof Integer;
            case "J" -> value instanceof Long;
            case "Ljava/lang/String;" -> value instanceof String;
            default -> false;
        };
    }
    private record Initialization(ClassNode owner,MethodNode clinit,Set<AbstractInsnNode> suffix) {}
    /** Separate a closed table-initialization region from a suffix that cannot access its array aliases. */
    private static Initialization initialization(JvmTransformerContext context,ClassNode owner,MethodNode helper,FieldNode cache) {
        MethodNode original=owner.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
        if(EvaluationPolicy.initializer(context,owner))return new Initialization(owner,original,Set.of());
        // This prefix proof is deliberately limited to the classic two private String-array family.
        List<FieldNode> fields=owner.fields.stream().filter(f->(f.access&ACC_STATIC)!=0).toList();
        if(fields.size()!=2 || fields.stream().anyMatch(f->!f.desc.equals(cache.desc) || (f.access&ACC_PRIVATE)==0) || !original.tryCatchBlocks.isEmpty())return null;
        FieldInsnNode cut=null;
        for(var insn:original.instructions)if(insn instanceof FieldInsnNode f && isCache(f,owner.name,cache) && f.getOpcode()==PUTSTATIC)cut=f;
        if(cut==null)return null;
        try {
            Frame<BasicValue>[] frames=new Analyzer<>(new BasicInterpreter()).analyze(owner.name,original);
            int after=original.instructions.indexOf(cut)+1;
            // A table reference left on the operand stack could escape into the business suffix.
            if(after>=frames.length || frames[after]==null || frames[after].getStackSize()!=0)return null;
        } catch(Exception invalid){return null;}
        Set<AbstractInsnNode> suffix=Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<AbstractInsnNode> work=new ArrayDeque<>();if(cut.getNext()!=null)work.add(cut.getNext());
        while(!work.isEmpty()) {
            AbstractInsnNode insn=work.removeFirst();if(!suffix.add(insn))continue;
            // No local alias from table construction, field access on this owner, or unknown own helper may escape.
            if(insn instanceof VarInsnNode || insn instanceof IincInsnNode || insn.getOpcode()==JSR || insn.getOpcode()==RET)return null;
            if(insn instanceof FieldInsnNode f && f.owner.equals(owner.name))return null;
            if(insn instanceof MethodInsnNode call && call.owner.equals(owner.name) && !isCall(call,owner.name,helper))return null;
            if(insn instanceof JumpInsnNode jump) {
                work.add(jump.label);if(jump.getOpcode()==GOTO)continue;
            } else if(insn instanceof TableSwitchInsnNode sw) {work.add(sw.dflt);work.addAll(sw.labels);continue;}
            else if(insn instanceof LookupSwitchInsnNode sw) {work.add(sw.dflt);work.addAll(sw.labels);continue;}
            if(insn.getOpcode()>=IRETURN && insn.getOpcode()<=RETURN || insn.getOpcode()==ATHROW)continue;
            if(insn.getNext()!=null)work.add(insn.getNext());
        }
        try {
            ClassNode prefixOwner=new ClassNode();owner.accept(prefixOwner);
            MethodNode prefix=prefixOwner.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElseThrow();
            AbstractInsnNode point=prefix.instructions.get(original.instructions.indexOf(cut));
            prefix.instructions.insert(point,new InsnNode(RETURN));
            Frame<BasicValue>[] frames=new Analyzer<>(new BasicInterpreter()).analyze(owner.name,prefix);
            AbstractInsnNode[] instructions=prefix.instructions.toArray();
            for(int i=0;i<instructions.length;i++)if(frames[i]==null || instructions[i] instanceof FrameNode || instructions[i] instanceof LineNumberNode)prefix.instructions.remove(instructions[i]);
            prefix.localVariables=null;
            if(!EvaluationPolicy.initializer(context,prefixOwner))return null;
            return new Initialization(prefixOwner,prefix,suffix);
        } catch(Exception invalid){return null;}
    }
    private static boolean isCall(MethodInsnNode call,String owner,MethodNode helper) {
        return call.getOpcode()==INVOKESTATIC && call.owner.equals(owner) && call.name.equals(helper.name) && call.desc.equals(helper.desc);
    }
    private static boolean isCache(FieldInsnNode field,String owner,FieldNode cache) {
        return field.owner.equals(owner) && field.name.equals(cache.name) && field.desc.equals(cache.desc);
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode insn) {
        AbstractInsnNode next=insn.getNext();while(next!=null && next.getOpcode()<0)next=next.getNext();return next;
    }
    static FieldNode findCache(ClassNode owner,MethodNode helper) {
        String element=switch(Type.getReturnType(helper.desc).getDescriptor()) {
            case "I" -> "java/lang/Integer";
            case "J" -> "java/lang/Long";
            default -> "java/lang/String";
        };
        for(FieldNode field:owner.fields) {
            if(!field.desc.equals("[L"+element+";") || (field.access&(ACC_PRIVATE|ACC_STATIC))!=(ACC_PRIVATE|ACC_STATIC))continue;
            List<FieldInsnNode> reads=new ArrayList<>();
            for(var insn:helper.instructions)if(insn instanceof FieldInsnNode read && isCache(read,owner.name,field))reads.add(read);
            if(reads.size()!=3 || reads.stream().anyMatch(r->r.getOpcode()!=GETSTATIC))continue;
            if(!(nextCode(reads.getFirst()) instanceof VarInsnNode index) || index.getOpcode()!=ILOAD)continue;
            boolean valid=true;int stores=0;
            for(var read:reads)if(!(nextCode(read) instanceof VarInsnNode load) || load.getOpcode()!=ILOAD || load.var!=index.var)valid=false;
            for(var insn:helper.instructions) {
                if(insn instanceof VarInsnNode v && v.getOpcode()==ISTORE && v.var==index.var)stores++;
                if(insn instanceof IincInsnNode inc && inc.var==index.var)valid=false;
            }
            // Index is either an unchanged int parameter or assigned once before any cache access.
            if(stores>1)valid=false;
            for(AbstractInsnNode insn=reads.getFirst();insn!=null;insn=insn.getNext())
                if(insn instanceof VarInsnNode v && v.getOpcode()==ISTORE && v.var==index.var)valid=false;
            AbstractInsnNode get=nextCode(index),branch=get==null?null:nextCode(get);
            if(get==null || get.getOpcode()!=AALOAD || !(branch instanceof JumpInsnNode jump) || jump.getOpcode()!=IFNONNULL)continue;
            AbstractInsnNode cachedReturn=jump.label;while(cachedReturn!=null && cachedReturn.getOpcode()<0)cachedReturn=cachedReturn.getNext();
            if(cachedReturn!=reads.getLast())continue;
            AbstractInsnNode resultLoad=nextCode(reads.getLast());
            AbstractInsnNode resultGet=resultLoad==null?null:nextCode(resultLoad);
            if(resultGet==null || resultGet.getOpcode()!=AALOAD)continue;
            AbstractInsnNode returning=nextCode(resultGet);
            if(!element.equals("java/lang/String")) {
                String kind=element.equals("java/lang/Integer")?"int":"long";
                if(!(returning instanceof MethodInsnNode unbox) || unbox.getOpcode()!=INVOKEVIRTUAL || !unbox.owner.equals(element)
                        || !unbox.name.equals(kind+"Value") || !unbox.desc.equals("()"+Type.getReturnType(helper.desc).getDescriptor()))continue;
                returning=nextCode(returning);
            }
            if(returning==null || returning.getOpcode()!=Type.getReturnType(helper.desc).getOpcode(IRETURN))continue;
            if(!valid)continue;
            try {
                // Cache aliases cannot escape. The only writes and reads must use the stable index local.
                var proof=new CacheAccessProof(new HashSet<>(reads),index.var);
                new Analyzer<>(proof).analyze(owner.name,helper);
                if(proof.reads!=2 || proof.writes!=1)continue;
            } catch(Exception failure){continue;}
            return field;
        }
        return null;
    }
    private static boolean closedReferences(ClassNode owner,MethodNode helper,FieldNode cache) {
        int writes=0;
        // Private bootstrap support left behind after successful reference inlining
        // cannot inspect a cache if no reachable method or handle can reach it.
        // Every non-private entry point and the initializer remains a root.
        var reachable=new HashSet<MethodNode>();var queue=new ArrayDeque<MethodNode>();
        for(var method:owner.methods)if((method.access&ACC_PRIVATE)==0||method.name.equals("<clinit>")||method==helper
                || method.visibleAnnotations!=null&&!method.visibleAnnotations.isEmpty() || method.invisibleAnnotations!=null&&!method.invisibleAnnotations.isEmpty()
                || Set.of("readObject","readObjectNoData","readResolve","writeObject","writeReplace").contains(method.name))queue.add(method);
        while(!queue.isEmpty()) {
            var method=queue.removeFirst();if(!reachable.add(method))continue;
            for(var insn:method.instructions)if(insn instanceof MethodInsnNode call && call.owner.equals(owner.name))
                owner.methods.stream().filter(m->m.name.equals(call.name)&&m.desc.equals(call.desc)).findFirst().ifPresent(queue::add);
        }
        for(MethodNode method:reachable) for(var insn:method.instructions) {
            if(insn instanceof LdcInsnNode ldc && (ldc.cst instanceof Handle || ldc.cst instanceof ConstantDynamic))return false;
            if(insn instanceof InvokeDynamicInsnNode indy && !standardConcat(indy))return false;
            if(insn instanceof MethodInsnNode call && (call.owner.startsWith("java/lang/reflect/") || call.owner.equals("java/lang/Class") || call.owner.startsWith("java/lang/invoke/")))return false;
            if(insn instanceof FieldInsnNode field && isCache(field,owner.name,cache) && method!=helper) {
                if(!method.name.equals("<clinit>") || field.getOpcode()!=PUTSTATIC)return false;
                writes++;
            }
        }
        return writes==1;
    }
    private static boolean standardConcat(InvokeDynamicInsnNode call) {
        if(call.bsm.getTag()!=H_INVOKESTATIC || !call.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory") || call.bsm.isInterface())return false;
        if(call.bsm.getName().equals("makeConcat"))return call.bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;") && call.bsmArgs.length==0;
        if(!call.bsm.getName().equals("makeConcatWithConstants") || !call.bsm.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"))return false;
        return Arrays.stream(call.bsmArgs).allMatch(v->v instanceof String || v instanceof Number);
    }
    private static final class CacheAccessProof extends SourceInterpreter {
        final Set<FieldInsnNode> origins;final int index;final Set<AbstractInsnNode> seenReads=new HashSet<>(),seenWrites=new HashSet<>();int reads,writes;
        CacheAccessProof(Set<FieldInsnNode> origins,int index){super(ASM9);this.origins=origins;this.index=index;}
        boolean cache(SourceValue value){return value.insns.stream().anyMatch(origins::contains);}
        void reject(SourceValue... values){for(var value:values)if(cache(value))throw new IllegalStateException("cache escape");}
        boolean index(SourceValue value){return !value.insns.isEmpty() && value.insns.stream().allMatch(i->i instanceof VarInsnNode v && v.getOpcode()==ILOAD && v.var==index);}
        @Override public SourceValue copyOperation(AbstractInsnNode insn,SourceValue value){return cache(value)?value:super.copyOperation(insn,value);}
        @Override public SourceValue unaryOperation(AbstractInsnNode insn,SourceValue value){reject(value);return super.unaryOperation(insn,value);}
        @Override public SourceValue binaryOperation(AbstractInsnNode insn,SourceValue left,SourceValue right){
            if(cache(left) && insn.getOpcode()==AALOAD && index(right)){if(seenReads.add(insn))reads++;return super.binaryOperation(insn,left,right);}
            reject(left,right);return super.binaryOperation(insn,left,right);
        }
        @Override public SourceValue ternaryOperation(AbstractInsnNode insn,SourceValue array,SourceValue at,SourceValue value){
            if(cache(array) && insn.getOpcode()==AASTORE && index(at) && !cache(value)){if(seenWrites.add(insn))writes++;return null;}
            reject(array,at,value);return super.ternaryOperation(insn,array,at,value);
        }
        @Override public SourceValue naryOperation(AbstractInsnNode insn,List<? extends SourceValue> values){for(var value:values)reject(value);return super.naryOperation(insn,values);}
        @Override public void returnOperation(AbstractInsnNode insn,SourceValue value,SourceValue expected){reject(value);}
    }
}
