package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.util.analysis.eval.*;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import java.util.*;

/** Folds closed constant-expression trees, including Skid-style fresh byte-array decoder arguments. */
public final class ExpressionFoldingTransformer implements JvmClassTransformer, Opcodes {
    private final InheritanceGraphService graphs;
    public ExpressionFoldingTransformer(InheritanceGraphService graphs) { this.graphs = graphs; }
    @Override public String identifier() { return "lightning.strings.closed-expression"; }
    @Override public void transform(JvmTransformerContext context, Workspace workspace, WorkspaceResource resource,
            JvmClassBundle bundle, JvmClassInfo info) {
        ClassNode node = context.getNode(bundle, info);
        var graph = graphs.getOrCreateInheritanceGraph(workspace);
        int changes = 0, attempts = 0;
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC) continue;
            Type resultType = Type.getReturnType(call.desc);
            if (resultType.getSort() == Type.VOID || !EvaluationPolicy.immutableArgument(resultType)) continue;
            AbstractInsnNode first = expressionStart(call, 0);
            if (first == null) continue;
            var target = context.resolveMethod(call);
            if (target == null || ++attempts > 512) continue;
            MethodNode expression = new MethodNode(ACC_PRIVATE | ACC_STATIC, "lightning$expression", "()" + resultType.getDescriptor(), null, null);
            for (AbstractInsnNode n = first; n != call.getNext(); n = n.getNext()) expression.instructions.add(n.clone(new HashMap<>()));
            expression.instructions.add(new InsnNode(resultType.getOpcode(IRETURN)));
            expression.maxStack = 128;
            if (!EvaluationPolicy.pure(context, node, expression)) continue;
            var evaluator = new Evaluator(workspace, context.newInterpreter(graph), new FieldCacheManager(), 60_000, false, false);
            EvaluationPolicy.installOverrides(evaluator, context, node, expression);
            EvaluationResult result = evaluator.evaluate(node, expression, null, List.of());
            if (!(result instanceof EvaluationYieldResult yielded)) continue;
            AbstractInsnNode replacement = OpaqueConstantFoldingTransformer.toInsn(yielded.value());
            if (replacement == null) continue;
            method.instructions.insertBefore(first, replacement);
            for (AbstractInsnNode n = first; n != call;) {
                AbstractInsnNode next = n.getNext(); method.instructions.remove(n); n = next;
            }
            method.instructions.remove(call);
            changes++;
        }
        if (changes > 0) { context.setRecomputeFrames(node.name); context.setNode(bundle, info, node); }
    }
    static AbstractInsnNode expressionStart(AbstractInsnNode insn, int depth) {
        if (insn == null || depth > 32) return null;
        int op = insn.getOpcode();
        if (insn instanceof LdcInsnNode ldc && (ldc.cst instanceof String || ldc.cst instanceof Number)) return insn;
        if (op >= ICONST_M1 && op <= DCONST_1 || op == BIPUSH || op == SIPUSH || op == ACONST_NULL) return insn;
        if (!(insn instanceof MethodInsnNode call) || op != INVOKESTATIC || Type.getReturnType(call.desc).getSort() == Type.VOID) return null;
        AbstractInsnNode start = call;
        for (int i = Type.getArgumentTypes(call.desc).length - 1; i >= 0; i--) {
            start = expressionStart(start.getPrevious(), depth + 1);
            if (start == null) return null;
        }
        return start;
    }
}
