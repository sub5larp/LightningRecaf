package recafx.deobf;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import software.coley.recaf.services.transform.JvmTransformerContext;
import software.coley.recaf.services.deobfuscation.transform.generic.StaticValueCollectionTransformer;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.util.analysis.ReAnalyzer;
import software.coley.recaf.util.analysis.ReInterpreter;
import software.coley.recaf.util.analysis.lookup.*;
import software.coley.recaf.util.analysis.value.*;
import java.util.*;

/** A bounded, fail-closed audit of the bytecode closure BEFORE using Recaf's interpreter. */
public final class EvaluationPolicy implements Opcodes {
    private EvaluationPolicy() {}
    /** Caller frames must not turn random/locale-dependent calls or mutable heap loads into facts. */
    public static ReAnalyzer frameAnalyzer(JvmTransformerContext context, InheritanceGraph graph, GetStaticLookup constants) {
        var defaults=context.newInterpreter(graph);
        var interpreter=new ReInterpreter(graph) {
            @Override public ReValue binaryOperation(AbstractInsnNode insn,ReValue left,ReValue right) {
                return switch(insn.getOpcode()) {
                    case IALOAD,BALOAD,CALOAD,SALOAD -> IntValue.UNKNOWN;
                    case LALOAD -> LongValue.UNKNOWN;
                    case FALOAD -> FloatValue.UNKNOWN;
                    case DALOAD -> DoubleValue.UNKNOWN;
                    case AALOAD -> ObjectValue.VAL_OBJECT_MAYBE_NULL;
                    default -> super.binaryOperation(insn,left,right);
                };
            }
        };
        interpreter.setGetStaticLookup(constants);
        var statics=defaults.getInvokeStaticLookup();
        if(statics!=null)interpreter.setInvokeStaticLookup(new InvokeStaticLookup() {
            @Override public boolean hasLookup(MethodInsnNode call){return jdkCall(call) && immutableSignature(call) && statics.hasLookup(call);}
            @Override public ReValue get(MethodInsnNode call,List<? extends ReValue> arguments){return hasLookup(call)?statics.get(call,arguments):unknown(Type.getReturnType(call.desc));}
        });
        var virtuals=defaults.getInvokeVirtualLookup();
        if(virtuals!=null)interpreter.setInvokeVirtualLookup(new InvokeVirtualLookup() {
            @Override public boolean hasLookup(MethodInsnNode call){return call.owner.equals("java/lang/String") && jdkCall(call) && immutableSignature(call) && virtuals.hasLookup(call);}
            @Override public ReValue get(MethodInsnNode call,ReValue receiver,List<? extends ReValue> arguments){return hasLookup(call)?virtuals.get(call,receiver,arguments):unknown(Type.getReturnType(call.desc));}
        });
        return new ReAnalyzer(interpreter);
    }
    private static boolean immutableSignature(MethodInsnNode call) {
        return immutableArgument(Type.getReturnType(call.desc)) && Arrays.stream(Type.getArgumentTypes(call.desc)).allMatch(EvaluationPolicy::immutableArgument);
    }
    private static ReValue unknown(Type type) {
        try{return ReValue.ofType(type,software.coley.recaf.util.analysis.Nullness.UNKNOWN);}
        catch(IllegalValueException invalid){return UninitializedValue.UNINITIALIZED_VALUE;}
    }
    /** Keep interpretation and auditing on the same transformed definitions. */
    public static void installOverrides(software.coley.recaf.util.analysis.eval.Evaluator evaluator,
                                        JvmTransformerContext context, ClassNode owner, MethodNode method) {
        installOverrides(evaluator,context,owner,method,false);
    }
    public static void installMetadataOverrides(software.coley.recaf.util.analysis.eval.Evaluator evaluator,
                                        JvmTransformerContext context, ClassNode owner, MethodNode method) {
        installOverrides(evaluator,context,owner,method,true);
    }
    private static void installOverrides(software.coley.recaf.util.analysis.eval.Evaluator evaluator,
                                        JvmTransformerContext context, ClassNode owner, MethodNode method,boolean metadata) {
        evaluator.registerClassNodeOverride(owner);
        Set<String> visited=new HashSet<>();
        Deque<software.coley.recaf.util.ClassMethodPair> queue=new ArrayDeque<>();
        queue.add(new software.coley.recaf.util.ClassMethodPair(owner,method));
        if(method.name.equals("<clinit>")) {
            var hierarchy=initializationTypes(context,owner);
            if(hierarchy!=null)for(var parent:hierarchy) {
                evaluator.registerClassNodeOverride(parent);
                for(var init:parent.methods)if(init.name.equals("<clinit>"))queue.add(new software.coley.recaf.util.ClassMethodPair(parent,init));
            }
        }
        while(!queue.isEmpty() && visited.size()<128) {
            var item=queue.removeFirst();
            if(!visited.add(item.classNode().name+'.'+item.methodNode().name+item.methodNode().desc))continue;
            evaluator.registerClassNodeOverride(item.classNode());
            for(var insn:item.methodNode().instructions) if(insn instanceof MethodInsnNode call && call.getOpcode()==INVOKESTATIC && !jdkCall(call) && !(metadata && ZkmMetadataInterpreter.supports(call))) {
                var target=context.resolveMethod(call);if(target!=null)queue.add(target);
            }
        }
    }
    public static boolean immutableArgument(Type type) {
        return type.getSort() < Type.ARRAY || type.equals(Type.getType(String.class));
    }
    public static boolean pure(JvmTransformerContext context, ClassNode owner, MethodNode method) {
        return pure(context, owner, method, new HashSet<>(), new int[]{0}, null, null);
    }
    public static boolean withConstants(JvmTransformerContext context, ClassNode owner, MethodNode method,
                                        StaticValueCollectionTransformer constants) {
        return pure(context, owner, method, new HashSet<>(), new int[]{0}, null, constants);
    }
    public static boolean initializer(JvmTransformerContext context, ClassNode owner) {
        // Root reference values may escape through a public field. Keep those
        // initializers out of constant collection; scalar assignments are harmless.
        // Ancestor/interface state is only audited for isolation, never imported as facts.
        Set<String> initializingMethods=GuardedStaticValueInliner.collectInitializationMethods(owner);
        for(var method:owner.methods)if(initializingMethods.contains(method.name+method.desc))for(var insn:method.instructions)
            if(insn instanceof FieldInsnNode field && field.getOpcode()==PUTSTATIC && field.owner.equals(owner.name)
                && !immutableArgument(Type.getType(field.desc)) && owner.fields.stream().anyMatch(f->f.name.equals(field.name)&&f.desc.equals(field.desc)&&(f.access&ACC_PRIVATE)==0))return false;
        // Every superclass/interface initializer must remain confined to its own state.
        // In particular, a parent that calls back into this child is rejected before interpretation.
        var hierarchy=initializationTypes(context,owner);if(hierarchy==null)return false;
        int[] budget={0};
        for(var type:hierarchy)for(MethodNode method:type.methods)if(method.name.equals("<clinit>"))
            if(!pure(context,type,method,new HashSet<>(),budget,type.name,null))return false;
        return true;
    }
    private static List<ClassNode> initializationTypes(JvmTransformerContext context,ClassNode owner) {
        var result=new ArrayList<ClassNode>();var seen=new HashSet<String>();var pending=new ArrayDeque<ClassNode>();pending.add(owner);
        while(!pending.isEmpty()) {
            var node=pending.removeFirst();if(!seen.add(node.name))continue;if(seen.size()>48)return null;result.add(node);
            var names=new ArrayList<String>(node.interfaces);if(node.superName!=null)names.add(node.superName);
            for(String name:names) {
                if(name.equals("java/lang/Object")||seen.contains(name))continue;
                var path=context.getWorkspace().findJvmClass(name);if(path==null)return null;
                var bundle=path.getValueOfType(software.coley.recaf.workspace.model.bundle.JvmClassBundle.class);if(bundle==null)return null;
                pending.add(context.getNode(bundle,path.getValue().asJvmClass()));
            }
        }
        return result;
    }
    /** Metadata-only reflection is available exclusively through ZkmMetadataInterpreter. */
    public static boolean metadataInitializer(JvmTransformerContext context,ClassNode owner) {
        if(!"java/lang/Object".equals(owner.superName))return false;
        for(var method:owner.methods)if(method.name.equals("<clinit>"))
            return pure(context,owner,method,new HashSet<>(),new int[]{0},owner.name,null,true);
        return true;
    }
    public static boolean metadataMethod(JvmTransformerContext context,ClassNode owner,MethodNode method) {
        return pure(context,owner,method,new HashSet<>(),new int[]{0},owner.name,null,true);
    }
    private static boolean pure(JvmTransformerContext context, ClassNode owner, MethodNode method,
                                Set<String> visiting, int[] budget, String initializing, StaticValueCollectionTransformer constants) {
        return pure(context,owner,method,visiting,budget,initializing,constants,false);
    }
    private static boolean pure(JvmTransformerContext context, ClassNode owner, MethodNode method,
                                Set<String> visiting, int[] budget, String initializing, StaticValueCollectionTransformer constants,boolean metadata) {
        String key = owner.name + '.' + method.name + method.desc;
        if (++budget[0] > 128) return false;
        // Metadata searches recurse through type hierarchies. Every method body is still audited,
        // and the interpreter independently bounds actual recursive execution.
        if(!visiting.add(key))return metadata;
        try {
            if ((method.access & (ACC_NATIVE | ACC_ABSTRACT | ACC_SYNCHRONIZED)) != 0) return false;
            if (method.instructions.size() > 12000) return false;
            Frame<SourceValue>[] frames = null;
            for (AbstractInsnNode insn : method.instructions) {
                int op = insn.getOpcode();
                if (op == MONITORENTER || op == MONITOREXIT || op == GETFIELD || op == PUTFIELD
                        || insn instanceof InvokeDynamicInsnNode) return false;
                if (insn instanceof LdcInsnNode ldc && !(ldc.cst instanceof String || ldc.cst instanceof Number || (metadata || initializing!=null) && ldc.cst instanceof Type)) return false;
                if (insn instanceof FieldInsnNode field) {
                    if((metadata || initializing!=null) && ZkmMetadataInterpreter.primitiveType(field)!=null)continue;
                    if (field.owner.equals(initializing) && owner.fields.stream().anyMatch(f -> f.name.equals(field.name)
                            && f.desc.equals(field.desc) && ((f.access & ACC_PRIVATE) != 0
                            // Initialization may assign its own public fields. Their values
                            // are not assumed immutable: later reads still need the field collector's proof.
                            || op == PUTSTATIC
                            || (f.access & (ACC_STATIC | ACC_FINAL)) == (ACC_STATIC | ACC_FINAL)
                            && immutableArgument(Type.getType(f.desc))))) continue;
                    if (op != GETSTATIC) return false;
                    if (constants != null && constants.hasLookup(field)
                            && (field.owner.equals(owner.name) || !constants.isInitializationObservable(field.owner))) continue;
                    if (field.owner.equals("java/nio/charset/StandardCharsets") && field.desc.equals("Ljava/nio/charset/Charset;")) continue;
                    if (field.owner.equals(owner.name) && owner.fields.stream().anyMatch(f -> f.name.equals(field.name)
                            && f.desc.equals(field.desc) && (f.access & (ACC_STATIC | ACC_FINAL)) == (ACC_STATIC | ACC_FINAL) && f.value != null)) continue;
                    return false;
                }
                if (insn instanceof TypeInsnNode type && op == NEW && !freshType(type.desc)) return false;
                if (insn instanceof MethodInsnNode call) {
                    if (call.owner.equals("javax/crypto/Cipher") && call.name.equals("init")) {
                        // Encryption may generate a random IV. Only statically explicit DECRYPT_MODE is deterministic.
                        try {
                            if (frames == null) frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
                            Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
                            if (frame == null) return false;
                            SourceValue mode = frame.getStack(frame.getStackSize() - Type.getArgumentTypes(call.desc).length);
                            if (mode.insns.isEmpty() || mode.insns.stream().anyMatch(n -> n.getOpcode() != ICONST_2)) return false;
                        } catch (Exception e) { return false; }
                    }
                    if (jdkCall(call) || metadata && ZkmMetadataInterpreter.supports(call)) continue;
                    if (op != INVOKESTATIC) return false;
                    var target = context.resolveMethod(call);
                    if (target == null) return false;
                    // Removing a cross-class invocation must not suppress a class initializer.
                    if (!target.classNode().name.equals(owner.name) && target.classNode().methods.stream()
                            .anyMatch(m -> m.name.equals("<clinit>"))
                            && (constants == null || constants.isInitializationObservable(target.classNode().name))) return false;
                    if (!pure(context, target.classNode(), target.methodNode(), visiting, budget, initializing, constants,metadata)) return false;
                }
            }
            return true;
        } finally { visiting.remove(key); }
    }
    private static boolean freshType(String owner) {
        return Set.of("java/lang/String", "java/lang/StringBuilder", "java/lang/StringBuffer",
                "javax/crypto/spec/SecretKeySpec", "javax/crypto/spec/IvParameterSpec", "javax/crypto/spec/DESKeySpec",
                "javax/crypto/spec/DESedeKeySpec", "java/lang/IllegalArgumentException", "java/lang/IllegalStateException",
                "java/lang/RuntimeException").contains(owner);
    }
    static boolean jdkCall(MethodInsnNode call) {
        String owner = call.owner, name = call.name;
        if (name.equals("<init>")) return freshType(owner);
        return switch (owner) {
            case "java/lang/String" -> Set.of("length", "charAt", "toCharArray", "getBytes", "substring", "concat",
                    "equals", "equalsIgnoreCase", "hashCode", "indexOf", "lastIndexOf", "valueOf", "copyValueOf", "intern", "toString", "isEmpty").contains(name)
                    && !call.desc.contains("Ljava/lang/Object;");
            case "java/lang/StringBuilder", "java/lang/StringBuffer" -> Set.of("append", "reverse", "toString", "length", "charAt", "setCharAt").contains(name)
                    && !call.desc.contains("Ljava/lang/Object;");
            case "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte", "java/lang/Character",
                    "java/lang/Float", "java/lang/Double", "java/lang/Boolean" -> Set.of("parseInt", "parseLong", "parseShort", "parseByte",
                    "parseFloat", "parseDouble", "toString", "toHexString", "toBinaryString", "reverse", "reverseBytes", "rotateLeft", "rotateRight",
                    "numberOfLeadingZeros", "numberOfTrailingZeros", "bitCount", "intBitsToFloat", "floatToIntBits", "floatToRawIntBits",
                    "longBitsToDouble", "doubleToLongBits", "doubleToRawLongBits", "compare", "compareUnsigned", "divideUnsigned", "remainderUnsigned",
                    "valueOf", "intValue", "longValue", "byteValue", "shortValue", "floatValue", "doubleValue", "charValue", "booleanValue").contains(name);
            case "java/lang/Math", "java/lang/StrictMath" -> !name.equals("random");
            case "java/util/Base64" -> Set.of("getDecoder", "getEncoder", "getUrlDecoder", "getUrlEncoder", "getMimeDecoder").contains(name);
            case "java/util/Base64$Decoder" -> name.equals("decode");
            case "java/util/Base64$Encoder" -> Set.of("encode", "encodeToString", "withoutPadding").contains(name);
            case "java/util/Arrays" -> Set.of("copyOf", "copyOfRange", "fill", "equals").contains(name) && !call.desc.contains("Ljava/lang/Object;");
            case "java/security/MessageDigest" -> Set.of("getInstance", "digest", "update", "reset").contains(name);
            case "javax/crypto/Cipher" -> Set.of("getInstance", "init", "doFinal", "update").contains(name) && !call.desc.contains("SecureRandom");
            case "javax/crypto/SecretKeyFactory" -> Set.of("getInstance", "generateSecret").contains(name) && !call.desc.contains("Provider");
            case "java/nio/ByteBuffer" -> Set.of("wrap", "allocate", "get", "put", "getInt", "getLong", "putInt", "putLong", "array", "asCharBuffer", "order").contains(name);
            case "java/nio/CharBuffer" -> Set.of("toString", "get", "put", "array").contains(name);
            default -> false;
        };
    }

    /** Guard upstream arithmetic folds against their broad host-library lookup table. */
    public static boolean hasEnvironmentDependentCalls(ClassNode node) {
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if ((call.owner.equals("java/lang/Math") || call.owner.equals("java/lang/StrictMath")) && call.name.equals("random")) return true;
            if (call.owner.equals("java/lang/System") || call.owner.equals("java/lang/Thread")
                    || call.owner.equals("java/util/Random") || call.owner.equals("java/security/SecureRandom")
                    || call.owner.startsWith("java/time/") || call.owner.equals("java/util/Locale")) return true;
            if (call.owner.equals("java/lang/String") && Set.of("format", "toLowerCase", "toUpperCase").contains(call.name)) return true;
        }
        return false;
    }
}
