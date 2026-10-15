package recafx.deobf;

import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueCollectionTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

/** Recaf's field/array proof, limited to initializer closures that pass our static audit. */
public class GuardedStaticValueCollector extends StaticValueCollectionTransformer {
    private Workspace currentWorkspace;
    public GuardedStaticValueCollector(InheritanceGraphService graph) { super(graph); }
    @Override public void setup(JvmTransformerContext context,Workspace workspace) {
        currentWorkspace=workspace;super.setup(context,workspace);
    }
    @Override public software.coley.recaf.util.analysis.value.ReValue getStaticValue(String owner,String name,String descriptor) {
        // A public final array can still be changed by callers outside this workspace.
        // Only a private array can use the collector's complete local alias proof.
        if(descriptor.startsWith("[")) {
            var path=currentWorkspace==null?null:currentWorkspace.findJvmClass(owner);
            var field=path==null?null:path.getValue().asJvmClass().getDeclaredField(name,descriptor);
            if(field==null || !field.hasPrivateModifier())return null;
        }
        return super.getStaticValue(owner,name,descriptor);
    }
    @Override public String identifier() { return "lightning.strings.static-collect"; }
    @Override protected void configureEvaluator(software.coley.recaf.util.analysis.eval.Evaluator evaluator,
            JvmTransformerContext context,org.objectweb.asm.tree.ClassNode owner,org.objectweb.asm.tree.MethodNode initializer) {
        EvaluationPolicy.installOverrides(evaluator,context,owner,initializer);
    }
    @Override public void transform(JvmTransformerContext context, Workspace workspace, WorkspaceResource resource,
            JvmClassBundle bundle, JvmClassInfo info) throws TransformationException {
        if (EvaluationPolicy.initializer(context, context.getNode(bundle, info)))
            super.transform(context, workspace, resource, bundle, info);
    }
}
