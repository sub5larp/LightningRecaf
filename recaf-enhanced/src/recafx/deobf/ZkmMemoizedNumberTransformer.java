package recafx.deobf;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import java.util.Set;
/** ZKM cached XOR/DES int and long constants with proven per-slot caller agreement. */
public final class ZkmMemoizedNumberTransformer extends ZkmMemoizedValueTransformer {
    public ZkmMemoizedNumberTransformer(InheritanceGraphService graphs){super(graphs);}
    @Override public String identifier(){return "lightning.zkm.memoized-numbers";}
    @Override protected boolean accepts(String descriptor){return Set.of("(IJ)I","(IJ)J").contains(descriptor);}
}
