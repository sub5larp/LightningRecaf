package recafx.deobf;
import org.objectweb.asm.tree.ClassNode;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
/** Shared frame recomputation for small local rewrites. */
public abstract class LocalRewriteTransformer implements JvmClassTransformer {
    public abstract int rewrite(ClassNode node);
    @Override public final void transform(JvmTransformerContext context, Workspace workspace, WorkspaceResource resource,
                                         JvmClassBundle bundle, JvmClassInfo info) {
        ClassNode node=context.getNode(bundle,info);
        if(rewrite(node)>0) { context.setRecomputeFrames(node.name); context.setNode(bundle,info,node); }
    }
}
