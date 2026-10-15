package recafx.deobf;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.util.analysis.eval.*;
import software.coley.recaf.util.analysis.value.*;
import software.coley.recaf.util.analysis.value.impl.*;
import software.coley.recaf.workspace.model.Workspace;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Shows useful initial-state plaintext when a cache/reflective-access proof is
 * incomplete. This diagnostic NEVER substitutes bytecode or changes a cache. */
public final class ZkmStringDiagnostics implements Opcodes {
    private ZkmStringDiagnostics(){}
    public static Map<String,List<String>> scan(Workspace workspace,InheritanceGraph graph,BooleanSupplier cancelled) {
        var notes=new LinkedHashMap<String,List<String>>();
        scanCandidates(workspace,graph,cancelled).forEach((owner,candidates)->notes.put(owner,candidates.stream().map(Candidate::note).toList()));
        return notes;
    }
    public record Candidate(String method,String descriptor,int instruction,String value) {
        public String note(){return "Initial-state string candidate: "+printable(method+descriptor)+" #"+instruction+" -> "+quoted(value);}
    }
    /** Structured evidence for corpus checks and integrations; still diagnostic only. */
    public static Map<String,List<Candidate>> scanCandidates(Workspace workspace,InheritanceGraph graph,BooleanSupplier cancelled) {
        var notes=new LinkedHashMap<String,List<Candidate>>();var context=new JvmTransformerContext(workspace,workspace.getPrimaryResource());
        int classes=0,attempts=0;long bytes=0;
        for(var bundle:workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().toList())for(var info:bundle.values()) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||++classes>2048||(bytes+=info.getBytecode().length)>32_000_000)return notes;
            try {
                var owner=context.getNode(bundle,info);var helpers=new HashMap<String,MethodNode>();
                for(var method:owner.methods)if((method.access&(ACC_PRIVATE|ACC_STATIC))==(ACC_PRIVATE|ACC_STATIC)
                    && Set.of("(II)Ljava/lang/String;","(III)Ljava/lang/String;","(IJ)Ljava/lang/String;").contains(method.desc)
                    && ZkmMemoizedValueTransformer.findCache(owner,method)!=null && EvaluationPolicy.metadataMethod(context,owner,method))helpers.put(method.name+method.desc,method);
                if(helpers.isEmpty()||!EvaluationPolicy.metadataInitializer(context,owner))continue;
                var clinit=owner.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElse(null);if(clinit==null)continue;
                var interpreter=new ZkmMetadataInterpreter(workspace,graph,context.newInterpreter(graph));var initialized=new FieldCacheManager();
                var init=new Evaluator(workspace,interpreter,initialized,300_000,false,true);EvaluationPolicy.installMetadataOverrides(init,context,owner,clinit);
                if(!(init.evaluateClassInitializer(owner) instanceof EvaluationYieldResult))continue;
                var initializationMethods=GuardedStaticValueInliner.collectInitializationMethods(owner);
                for(var method:owner.methods) {
                    // Initializer reads can see partially initialized tables. A completed snapshot
                    // cannot describe those sites, so they never receive a plaintext candidate.
                    if(initializationMethods.contains(method.name+method.desc)||method.instructions.size()>12000)continue;
                    Frame<ReValue>[] frames=null;var instructions=method.instructions.toArray();
                    for(int i=0;i<instructions.length;i++) {
                        if(!(instructions[i] instanceof MethodInsnNode call)||call.getOpcode()!=INVOKESTATIC||!call.owner.equals(owner.name))continue;
                        var helper=helpers.get(call.name+call.desc);if(helper==null)continue;
                        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||++attempts>256)return notes;
                        try {
                            if(frames==null)frames=EvaluationPolicy.frameAnalyzer(context,graph,null).analyze(owner.name,method);
                            var frame=frames[i];int count=Type.getArgumentCount(call.desc);if(frame==null||frame.getStackSize()<count)continue;
                            var arguments=new ArrayList<ReValue>();for(int j=0;j<count;j++)arguments.add(frame.getStack(frame.getStackSize()-count+j));
                            if(arguments.stream().anyMatch(v->v==null||!v.hasKnownValue()))continue;
                            // Each call starts from the same independently copied initialization state.
                            // One candidate can neither warm nor poison another candidate's cache.
                            var state=copy(owner,initialized);var evaluator=new Evaluator(workspace,interpreter,state,100_000,false,false);
                            EvaluationPolicy.installMetadataOverrides(evaluator,context,owner,helper);var result=evaluator.evaluate(owner,helper,null,arguments);
                            if(result instanceof EvaluationYieldResult yield) {
                                ReValue value=yield.value();if(value instanceof InstancedObjectValue<?> instance)value=instance.unmap();
                                if(value instanceof StringValue text&&text.getText().isPresent())notes.computeIfAbsent(owner.name,ignored->new ArrayList<>())
                                    .add(new Candidate(method.name,method.desc,i,text.getText().get()));
                            }
                        } catch(Exception | LinkageError unsupported) { /* Keep unresolved calls as they are. */ }
                    }
                }
            }catch(Exception | LinkageError unsupported){ /* No note is better than an invented value. */ }
        }
        return notes;
    }
    private static FieldCacheManager copy(ClassNode owner,FieldCacheManager source) {
        var result=new FieldCacheManager();var seen=new IdentityHashMap<ReValue,ReValue>();var active=Collections.newSetFromMap(new IdentityHashMap<ReValue,Boolean>());int[] budget={65536};
        for(var field:owner.fields)if((field.access&ACC_STATIC)!=0) {
            var value=source.getStaticFieldCache(owner.name).getField(owner.name,field.name,field.desc);if(value==null)throw new IllegalArgumentException("Missing initialized field");
            result.getStaticFieldCache(owner.name).setField(owner.name,field.name,field.desc,copy(value,seen,active,budget));
        }
        return result;
    }
    private static ReValue copy(ReValue value,IdentityHashMap<ReValue,ReValue> seen,Set<ReValue> active,int[] budget) {
        if(--budget[0]<0||active.contains(value))throw new IllegalArgumentException("Array snapshot bound/cycle");if(seen.containsKey(value))return seen.get(value);
        if(value instanceof InstancedObjectValue<?> instance) {
            Object raw=instance.getRealInstance();if(raw instanceof String text){var copied=ObjectValue.string(text);seen.put(value,copied);return copied;}
            ReValue unwrapped=instance.unmap();if(unwrapped==value)throw new IllegalArgumentException("Mutable host value retained");var copied=copy(unwrapped,seen,active,budget);seen.put(value,copied);return copied;
        }
        if(value instanceof ArrayValue array && !array.isNull()) {
            int length=array.getFirstDimensionLength().orElseThrow();if(length>budget[0])throw new IllegalArgumentException("Array snapshot bound");
            active.add(value);var copied=ArrayValueMutableImpl.wrap(new ArrayValueImpl(array.type(),array.nullness(),length,i->UninitializedValue.UNINITIALIZED_VALUE));
            for(int i=0;i<length;i++)copied.setValue(i,copy(array.getValue(i),seen,active,budget));active.remove(value);seen.put(value,copied);return copied;
        }
        if(value instanceof IntValue||value instanceof LongValue||value instanceof FloatValue||value instanceof DoubleValue||value instanceof StringValue
            ||value instanceof ObjectValueBoxImpl<?>||value instanceof ZkmMetadataInterpreter.MetadataValue||value instanceof ObjectValue object&&object.isNull())return value;
        throw new IllegalArgumentException("Unmodeled field snapshot");
    }
    private static String printable(String value){String text=value.replaceAll("[\\p{Cntrl}]","?");return text.substring(0,Math.min(text.length(),350));}
    private static String quoted(String value) {
        var out=new StringBuilder("\"");int limit=Math.min(value.length(),500);
        for(int i=0;i<limit;i++){char c=value.charAt(i);if(c=='\\'||c=='\"')out.append('\\').append(c);else if(c<32||c==127)out.append(String.format("\\u%04x",(int)c));else out.append(c);}
        return out.append('"').append(value.length()>limit?" (truncated)":"").toString();
    }
}
