package recafx.deobf;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.JvmClassTransformer;

import java.util.Set;

/** Automatic processing is explicit: adding a plugin never silently adds it to this pipeline. */
public final class TransformSafety {
    private TransformSafety() {}
    private static final Set<String> AUTOMATIC = Set.of(
        "DeadCodeRemovingTransformer", "DuplicateAnnotationRemovingTransformer",
        "DuplicateCatchMergingTransformer", "ExceptionCollectionTransformer", "FrameRemovingTransformer",
        "GotoInliningTransformer", "IllegalAnnotationRemovingTransformer", "IllegalSignatureRemovingTransformer",
        "IllegalVarargsRemovingTransformer", "OpaqueConstantFoldingTransformer", "OpaquePredicateFoldingTransformer",
        "RedundantTryCatchRemovingTransformer", "VariableFoldingTransformer", "VariableTableNormalizingTransformer",
        "DashOpaqueSeedFoldingTransformer", "ZkmParameterUnpackingTransformer"
    );
    public static boolean automatic(Class<? extends JvmClassTransformer> type) {
        return type.getPackageName().startsWith("software.coley.recaf.services.deobfuscation.transform.")
            && AUTOMATIC.contains(type.getSimpleName())
            || type == StringBuilderFoldingTransformer.class
            || type == PureCallFoldingTransformer.class
            || type == GuardedStaticValueCollector.class
            || type == GuardedStaticValueInliner.class
            || type == ExpressionFoldingTransformer.class
            || type == SkidStringRecoveryTransformer.class
            || type == ZkmStringRecoveryTransformer.class
            || type == ZkmMemoizedStringTransformer.class
            || type == ZkmMemoizedNumberTransformer.class
            || type == ZkmNumberRecoveryTransformer.class
            || type == SkidOpaqueMathTransformer.class
            || type == SkidExceptionFlowTransformer.class
            || type == ZkmExceptionBridgeTransformer.class
            || type == ConstantStringConcatTransformer.class
            || type == GuardedFlowTransformer.class
            || type == IntegerDomainTransformer.class
            || type == BranchConstraintTransformer.class
            || type == recafx.deobf.ported.SeededRandomFoldingTransformer.class
            || type == recafx.deobf.ported.NumericEncodingFoldingTransformer.class
            || type == MethodNormalizingTransformer.class;
    }
    public static boolean extended(Class<? extends JvmClassTransformer> type) {
        return type.getPackageName().equals("software.coley.recaf.services.deobfuscation.transform.generic")
            && Set.of("CallResultInliningTransformer", "StaticValueCollectionTransformer", "StaticValueInliningTransformer",
                "InvokeDynamicInliningTransformer").contains(type.getSimpleName());
    }

    public static boolean shouldEvaluate(JvmClassTransformer transformer, JvmClassInfo info) {
        if (!transformer.identifier().startsWith("peephole.data.") && !transformer.identifier().contains("opaque")
                && !(transformer instanceof GuardedStaticValueInliner)) return true;
        ClassNode node = new ClassNode();
        info.getClassReader().accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return !EvaluationPolicy.hasEnvironmentDependentCalls(node);
    }

    /** Structural/data-flow check only: never loads the analyzed class or resolves it through reflection. */
    public static String rejection(JvmClassInfo cls) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(cls.getBytecode()).accept(node, ClassReader.SKIP_FRAMES);
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            }
            return null;
        } catch (Exception | LinkageError error) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }
}
