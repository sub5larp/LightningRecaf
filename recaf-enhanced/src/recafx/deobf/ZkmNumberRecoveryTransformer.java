package recafx.deobf;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.services.inheritance.InheritanceGraphService;

/** DES/CBC/NoPadding numeric decryptors with known arguments and immutable table state. */
public final class ZkmNumberRecoveryTransformer extends InitializedDecoderTransformer {
    public ZkmNumberRecoveryTransformer(InheritanceGraphService graphs) { super(graphs); }
    @Override public String identifier() { return "lightning.zkm.des-numbers"; }
    @Override protected boolean accepts(MethodNode method) {
        return (method.desc.endsWith(")I") || method.desc.endsWith(")J")) && invokes(method, "javax/crypto/Cipher", "doFinal");
    }
}
