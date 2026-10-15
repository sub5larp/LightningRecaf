package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.util.analysis.Nullness;
import software.coley.recaf.util.analysis.eval.*;
import software.coley.recaf.util.analysis.value.*;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded evidence for reverse engineering. These are INITIAL-STATE candidates,
 * not a rewrite proof: Class.forName effects and mutable linkage state remain in
 * the original program. Target classes are never loaded into the host JVM. */
public final class ZkmReferenceDiagnostics {
    private ZkmReferenceDiagnostics(){}
    public static Map<String,List<String>> scan(Workspace workspace,InheritanceGraph graph,BooleanSupplier cancelled) {
        var context=new JvmTransformerContext(workspace,workspace.getPrimaryResource());
        var sessions=new HashMap<Handle,Session>();var rejected=new HashSet<Handle>();var notes=new LinkedHashMap<String,List<String>>();
        int classes=0,sites=0;long bytes=0;
        for(var bundle:workspace.getPrimaryResource().jvmAllClassBundleStreamRecursive().toList())for(var info:bundle.values()) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return notes;
            if(++classes>2048 || (bytes+=info.getBytecode().length)>32_000_000)return notes;
            ClassNode owner=context.getNode(bundle,info);
            for(var method:owner.methods) {
                if(method.instructions.size()>12000)continue;
                Frame<ReValue>[] frames=null;var instructions=method.instructions.toArray();
                for(int i=0;i<instructions.length;i++) {
                    if(!(instructions[i] instanceof InvokeDynamicInsnNode indy) || indy.bsm.getOwner().startsWith("java/") || indy.bsmArgs.length!=0 || !indy.bsm.getDesc().equals(ZkmBootstrapShape.BOOTSTRAP))continue;
                    if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()||++sites>64)return notes;
                    try {
                        Session session=sessions.get(indy.bsm);
                        if(session==null) {
                            if(rejected.contains(indy.bsm)||sessions.size()+rejected.size()>=8)continue;
                            session=open(context,workspace,graph,indy.bsm);
                            if(session==null){rejected.add(indy.bsm);continue;}sessions.put(indy.bsm,session);
                        }
                        if(frames==null)frames=EvaluationPolicy.frameAnalyzer(context,graph,null).analyze(owner.name,method);
                        var frame=frames[i];if(frame==null||frame.getStackSize()==0)continue;
                        Type[] types=Type.getArgumentTypes(indy.desc);if(types.length==0||!types[types.length-1].equals(Type.LONG_TYPE))continue;
                        ReValue key=frame.getStack(frame.getStackSize()-1);if(!(key instanceof LongValue number)||number.value().isEmpty())continue;
                        var result=session.evaluator.evaluate(session.owner,session.resolver,null,List.of(session.interpreter.lookup(owner.name),
                            ObjectValue.object(Type.getObjectType("java/lang/invoke/MutableCallSite"),Nullness.NOT_NULL),ObjectValue.string(indy.name),session.interpreter.methodType(Type.getMethodType(indy.desc)),key));
                        if(result instanceof EvaluationYieldResult yield && yield.value() instanceof ZkmMetadataInterpreter.HandleValue handle && handle.droppedArguments==1) {
                            notes.computeIfAbsent(owner.name,ignored->new ArrayList<>()).add("Initial-state ZKM reference candidate: "+printable(method.name+method.desc)+" #"+i+" -> "+printable(handle.target.toString()));
                        } else {sessions.remove(indy.bsm);rejected.add(indy.bsm);}
                    } catch(Exception | LinkageError unsupported) {
                        // An unknown branch, unsupported metadata type or exhausted interpreter budget
                        // leaves the site untouched and contributes no purported target.
                        sessions.remove(indy.bsm);rejected.add(indy.bsm);
                    }
                }
            }
        }
        return notes;
    }
    private static Session open(JvmTransformerContext context,Workspace workspace,InheritanceGraph graph,Handle bootstrap) {
        var path=workspace.findJvmClass(bootstrap.getOwner());if(path==null)return null;
        var bundle=path.getValueOfType(JvmClassBundle.class);if(bundle==null)return null;var owner=context.getNode(bundle,path.getValue().asJvmClass());
        var resolver=ZkmBootstrapShape.resolver(owner,bootstrap);
        if(resolver==null||!EvaluationPolicy.metadataInitializer(context,owner)||!EvaluationPolicy.metadataMethod(context,owner,resolver))return null;
        var clinit=owner.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElse(null);if(clinit==null)return null;
        var interpreter=new ZkmMetadataInterpreter(workspace,graph,context.newInterpreter(graph));var state=new FieldCacheManager();
        var init=new Evaluator(workspace,interpreter,state,300_000,false,true);EvaluationPolicy.installMetadataOverrides(init,context,owner,clinit);
        if(!(init.evaluateClassInitializer(owner) instanceof EvaluationYieldResult))return null;
        var evaluator=new Evaluator(workspace,interpreter,state,100_000,false,false);EvaluationPolicy.installMetadataOverrides(evaluator,context,owner,resolver);
        return new Session(owner,resolver,interpreter,evaluator);
    }
    private static String printable(String text){return text.replaceAll("[\\p{Cntrl}]","?").substring(0,Math.min(text.length(),600));}
    private record Session(ClassNode owner,MethodNode resolver,ZkmMetadataInterpreter interpreter,Evaluator evaluator){}
}
