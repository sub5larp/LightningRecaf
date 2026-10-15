package recafx.deobf;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.services.inheritance.InheritanceGraphService;

/** Skid byte/UTF-16 and pooled UTF-16BE salt decoders. Evaluate the actual helper; never infer it from its name. */
public final class SkidStringRecoveryTransformer extends InitializedDecoderTransformer {
    public SkidStringRecoveryTransformer(InheritanceGraphService graphs) { super(graphs); }
    @Override public String identifier() { return "lightning.skid.initialized-strings"; }
    @Override protected boolean accepts(MethodNode method) {
        return (method.desc.equals("([BI)Ljava/lang/String;") || method.desc.equals("([B[BI)Ljava/lang/String;"))
                && invokes(method, "java/lang/Integer", "toString") && invokes(method, "java/lang/String", "getBytes");
    }
}
