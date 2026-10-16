package recafx.deobf;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.TransformationManager;
/** Shared registration for the application and integration tests. */
public final class FamilyRecovery {
    private FamilyRecovery() {}
    public static void register(TransformationManager manager, InheritanceGraphService graphs) {
        manager.registerJvmClassTransformer(SkidStringRecoveryTransformer.class, () -> new SkidStringRecoveryTransformer(graphs));
        manager.registerJvmClassTransformer(ZkmStringRecoveryTransformer.class, () -> new ZkmStringRecoveryTransformer(graphs));
        manager.registerJvmClassTransformer(ZkmMemoizedStringTransformer.class, () -> new ZkmMemoizedStringTransformer(graphs));
        manager.registerJvmClassTransformer(ZkmMemoizedNumberTransformer.class, () -> new ZkmMemoizedNumberTransformer(graphs));
        manager.registerJvmClassTransformer(ZkmNumberRecoveryTransformer.class, () -> new ZkmNumberRecoveryTransformer(graphs));
        manager.registerJvmClassTransformer(SkidOpaqueMathTransformer.class, SkidOpaqueMathTransformer::new);
        manager.registerJvmClassTransformer(SkidExceptionFlowTransformer.class, SkidExceptionFlowTransformer::new);
        manager.registerJvmClassTransformer(ZkmExceptionBridgeTransformer.class, ZkmExceptionBridgeTransformer::new);
        manager.registerJvmClassTransformer(ConstantStringConcatTransformer.class, ConstantStringConcatTransformer::new);
        manager.registerJvmClassTransformer(GuardedFlowTransformer.class, () -> new GuardedFlowTransformer(graphs));
        manager.registerJvmClassTransformer(IntegerDomainTransformer.class,IntegerDomainTransformer::new);
        manager.registerJvmClassTransformer(BranchConstraintTransformer.class,BranchConstraintTransformer::new);
        manager.registerJvmClassTransformer(MixedBooleanArithmeticTransformer.class,MixedBooleanArithmeticTransformer::new);
    }
}
