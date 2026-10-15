package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Frame;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.util.analysis.eval.*;
import software.coley.recaf.util.analysis.value.ReValue;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;
import java.util.*;
import static software.coley.recaf.services.deobfuscation.transform.generic.OpaqueConstantFoldingTransformer.toInsn;

/** Constant-call recovery using audited, proven immutable initializer state. Target classes are never loaded. */
public abstract class InitializedDecoderTransformer implements JvmClassTransformer, Opcodes {
    private final InheritanceGraphService graphs;
    protected InitializedDecoderTransformer(InheritanceGraphService graphs) { this.graphs = graphs; }
    protected abstract boolean accepts(MethodNode target);
    @Override public Set<Class<? extends ClassTransformer>> dependencies() { return Set.of(GuardedStaticValueCollector.class); }

    @Override public void transform(JvmTransformerContext context, Workspace workspace, WorkspaceResource resource,
                                    JvmClassBundle bundle, JvmClassInfo info) throws TransformationException {
        ClassNode owner = context.getNode(bundle, info);
        var constants = context.getTransformer(GuardedStaticValueCollector.class);
        var graph = graphs.getOrCreateInheritanceGraph(workspace);
        Set<String> initialization = GuardedStaticValueInliner.collectInitializationMethods(owner);
        boolean dirty = false;
        int attempts = 0;
        for (MethodNode method : owner.methods) {
            if (initialization.contains(method.name + method.desc) || method.instructions.size() > 12000) continue;
            Frame<ReValue>[] frames = null;
            List<Rewrite> rewrites = new ArrayList<>();
            AbstractInsnNode[] instructions = method.instructions.toArray();
            for (int i = instructions.length - 1; i >= 0; i--) {
                if (!(instructions[i] instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC) continue;
                var target = context.resolveMethod(call);
                if (target == null || !accepts(target.methodNode())) continue;
                if (++attempts > 128) break;
                // Removing a call into another owner must preserve initialization side effects.
                if (!target.classNode().name.equals(owner.name) && constants.isInitializationObservable(target.classNode().name)) continue;
                if (!EvaluationPolicy.withConstants(context, target.classNode(), target.methodNode(), constants)) continue;
                Type returnType = Type.getReturnType(call.desc);
                if (returnType.getSort() == Type.VOID || !EvaluationPolicy.immutableArgument(returnType)) continue;
                var interpreter = context.newInterpreter(graph);
                interpreter.setGetStaticLookup(constants);
                var evaluator = new Evaluator(workspace, interpreter, new FieldCacheManager(), 60_000, false, false);
                evaluator.registerClassNodeOverride(owner);
                evaluator.registerClassNodeOverride(target.classNode());
                AbstractInsnNode first = ExpressionFoldingTransformer.expressionStart(call, 0);
                EvaluationResult result;
                if (first != null) {
                    // Includes freshly allocated byte/char arguments produced by pure helper calls.
                    MethodNode expression = new MethodNode(ACC_PRIVATE | ACC_STATIC, "lightning$initialized", "()" + returnType.getDescriptor(), null, null);
                    for (AbstractInsnNode n = first; n != call.getNext(); n = n.getNext()) expression.instructions.add(n.clone(new HashMap<>()));
                    expression.instructions.add(new InsnNode(returnType.getOpcode(IRETURN)));
                    expression.maxStack = 128;
                    if (!EvaluationPolicy.withConstants(context, owner, expression, constants)) continue;
                    EvaluationPolicy.installOverrides(evaluator, context, owner, expression);
                    result = evaluator.evaluate(owner, expression, null, List.of());
                } else {
                    // Preserve argument producers and their effects. Arrays require the fresh-expression route.
                    if (!Arrays.stream(Type.getArgumentTypes(call.desc)).allMatch(EvaluationPolicy::immutableArgument)) continue;
                    if (frames == null) {
                        try {
                            var analyzer = EvaluationPolicy.frameAnalyzer(context, graph, constants);
                            frames = analyzer.analyze(owner.name, method);
                        } catch (Exception error) { break; }
                    }
                    Frame<ReValue> frame = frames[i];
                    int count = Type.getArgumentTypes(call.desc).length;
                    if (frame == null || frame.getStackSize() < count) continue;
                    List<ReValue> arguments = new ArrayList<>();
                    for (int arg = 0; arg < count; arg++) arguments.add(frame.getStack(frame.getStackSize() - count + arg));
                    if (arguments.stream().anyMatch(v -> v == null || !v.hasKnownValue())) continue;
                    EvaluationPolicy.installOverrides(evaluator, context, target.classNode(), target.methodNode());
                    result = evaluator.evaluate(target.classNode(), target.methodNode(), null, arguments);
                }
                if (!(result instanceof EvaluationYieldResult yielded)) continue;
                AbstractInsnNode replacement = toInsn(yielded.value());
                if (replacement == null) continue;
                rewrites.add(new Rewrite(first, call, replacement));
                if (first != null) i = method.instructions.indexOf(first);
            }
            // Apply after analysis so earlier call sites keep their original frame indices.
            for (Rewrite rewrite : rewrites) {
                AbstractInsnNode first = rewrite.first, replacement = rewrite.replacement;
                MethodInsnNode call = rewrite.call;
                if (first != null) {
                    method.instructions.insertBefore(first, replacement);
                    for (AbstractInsnNode n = first; n != call;) {
                        AbstractInsnNode next = n.getNext(); method.instructions.remove(n); n = next;
                    }
                    method.instructions.remove(call);
                } else {
                    Type[] args = Type.getArgumentTypes(call.desc);
                    for (int arg = args.length - 1; arg >= 0; arg--) method.instructions.insertBefore(call, new InsnNode(args[arg].getSize() == 2 ? POP2 : POP));
                    method.instructions.set(call, replacement);
                }
                dirty = true;
            }
        }
        if (dirty) { context.setRecomputeFrames(owner.name); context.setNode(bundle, info, owner); }
    }
    private record Rewrite(AbstractInsnNode first, MethodInsnNode call, AbstractInsnNode replacement) {}
    protected static boolean invokes(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions)
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
        return false;
    }
}
