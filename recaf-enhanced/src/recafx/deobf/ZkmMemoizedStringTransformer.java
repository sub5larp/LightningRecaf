package recafx.deobf;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import java.util.Set;
/** Cached ZKM string helpers; cache and call-closure proofs are shared with numeric recovery. */
public final class ZkmMemoizedStringTransformer extends ZkmMemoizedValueTransformer {
    public ZkmMemoizedStringTransformer(InheritanceGraphService graphs){super(graphs);}
    @Override public String identifier(){return "lightning.zkm.memoized-strings";}
    @Override protected boolean accepts(String descriptor){return Set.of("(II)Ljava/lang/String;","(III)Ljava/lang/String;","(IJ)Ljava/lang/String;","(I)Ljava/lang/String;").contains(descriptor);}
}
