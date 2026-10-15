package recafx.deobf;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.services.inheritance.InheritanceGraphService;

/** Indexed ZKM-style XOR/DES string helpers backed by immutable static tables. */
public final class ZkmStringRecoveryTransformer extends InitializedDecoderTransformer {
    public ZkmStringRecoveryTransformer(InheritanceGraphService graphs) { super(graphs); }
    @Override public String identifier() { return "lightning.zkm.indexed-strings"; }
    @Override protected boolean accepts(MethodNode method) {
        return method.desc.endsWith(")Ljava/lang/String;") && (method.desc.startsWith("(I") || method.desc.startsWith("(J"));
    }
}
