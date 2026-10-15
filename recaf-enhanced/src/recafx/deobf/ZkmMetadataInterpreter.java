package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.util.analysis.*;
import software.coley.recaf.util.analysis.lookup.*;
import software.coley.recaf.util.analysis.value.*;
import software.coley.recaf.util.analysis.value.impl.*;
import software.coley.recaf.workspace.model.Workspace;
import java.util.*;

/** Reflection and method-handle metadata model. Never creates a host Class, Method,
 * Field, MethodHandle or CallSite for a target. Class.forName records a JVM effect
 * obligation; it only resolves ASM/workspace metadata here. */
public final class ZkmMetadataInterpreter extends ReInterpreter implements Opcodes {
    private final Workspace workspace;
    private final InheritanceGraph graph;
    private final Map<Type,ClassValue> classes=new HashMap<>();
    private final Set<String> initializationObligations=new LinkedHashSet<>();
    private final Set<Handle> resolutions=new LinkedHashSet<>();
    public ZkmMetadataInterpreter(Workspace workspace,InheritanceGraph graph,ReInterpreter defaults) {
        super(graph);this.workspace=workspace;this.graph=graph;
        var baseStatic=defaults.getInvokeStaticLookup();var baseVirtual=defaults.getInvokeVirtualLookup();
        setGetStaticLookup(new GetStaticLookup() {
            @Override public boolean hasLookup(FieldInsnNode field){return primitiveType(field)!=null;}
            @Override public ReValue get(FieldInsnNode field){Type primitive=primitiveType(field);return primitive==null?unknown(Type.getType(field.desc)):classValue(primitive);}
        });
        setInvokeStaticLookup(new InvokeStaticLookup() {
            @Override public boolean hasLookup(MethodInsnNode call){return supports(call) || EvaluationPolicy.jdkCall(call) && baseStatic!=null && baseStatic.hasLookup(call);}
            @Override public ReValue get(MethodInsnNode call,List<? extends ReValue> args){
                if(supports(call))return invoke(call,null,args);
                return hasLookup(call)?baseStatic.get(call,args):unknown(Type.getReturnType(call.desc));
            }
        });
        setInvokeVirtualLookup(new InvokeVirtualLookup() {
            @Override public boolean hasLookup(MethodInsnNode call){return supports(call) || EvaluationPolicy.jdkCall(call) && baseVirtual!=null && baseVirtual.hasLookup(call);}
            @Override public ReValue get(MethodInsnNode call,ReValue receiver,List<? extends ReValue> args){
                if(supports(call))return invoke(call,receiver,args);
                return hasLookup(call)?baseVirtual.get(call,receiver,args):unknown(Type.getReturnType(call.desc));
            }
        });
    }
    public Set<String> initializationObligations(){return Set.copyOf(initializationObligations);}
    public Set<Handle> resolutions(){return Set.copyOf(resolutions);}
    public LookupValue lookup(String caller){return new LookupValue(caller);}
    public ClassValue classValue(Type type){return classes.computeIfAbsent(type,ClassValue::new);}
    public MethodTypeValue methodType(Type type){return new MethodTypeValue(type);}
    @Override public ReValue newOperation(AbstractInsnNode insn)throws AnalyzerException {
        if(insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
            return type.getSort()==Type.METHOD?methodType(type):classValue(type);
        }
        return super.newOperation(insn);
    }
    static Type primitiveType(FieldInsnNode field) {
        if(!field.name.equals("TYPE") || !field.desc.equals("Ljava/lang/Class;") || field.getOpcode()!=GETSTATIC)return null;
        return switch(field.owner) {
            case "java/lang/Boolean"->Type.BOOLEAN_TYPE;case "java/lang/Byte"->Type.BYTE_TYPE;
            case "java/lang/Character"->Type.CHAR_TYPE;case "java/lang/Short"->Type.SHORT_TYPE;
            case "java/lang/Integer"->Type.INT_TYPE;case "java/lang/Long"->Type.LONG_TYPE;
            case "java/lang/Float"->Type.FLOAT_TYPE;case "java/lang/Double"->Type.DOUBLE_TYPE;
            case "java/lang/Void"->Type.VOID_TYPE;default->null;
        };
    }
    public static boolean supports(MethodInsnNode call) {
        String key=call.owner+'.'+call.name+call.desc;
        boolean isStatic=call.owner.equals("java/lang/invoke/MethodHandles") || call.owner.equals("java/lang/Class")&&call.name.equals("forName")
            || call.owner.equals("java/lang/invoke/MethodType")&&call.name.equals("methodType");
        return !call.itf && call.getOpcode()==(isStatic?INVOKESTATIC:INVOKEVIRTUAL) && SUPPORTED.contains(key);
    }
    private static final Set<String> SUPPORTED=Set.of(
        "java/lang/Object.getClass()Ljava/lang/Class;","java/lang/String.equals(Ljava/lang/Object;)Z",
        "java/lang/Exception.toString()Ljava/lang/String;","java/lang/reflect/Field.toString()Ljava/lang/String;","java/lang/reflect/Method.toString()Ljava/lang/String;",
        "java/lang/Class.forName(Ljava/lang/String;)Ljava/lang/Class;",
        "java/lang/Class.getName()Ljava/lang/String;","java/lang/Class.getSuperclass()Ljava/lang/Class;",
        "java/lang/Class.getInterfaces()[Ljava/lang/Class;","java/lang/Class.getDeclaredFields()[Ljava/lang/reflect/Field;",
        "java/lang/Class.getDeclaredMethods()[Ljava/lang/reflect/Method;",
        "java/lang/reflect/Field.getName()Ljava/lang/String;","java/lang/reflect/Field.getType()Ljava/lang/Class;",
        "java/lang/reflect/Field.getDeclaringClass()Ljava/lang/Class;",
        "java/lang/reflect/Method.getName()Ljava/lang/String;","java/lang/reflect/Method.getReturnType()Ljava/lang/Class;",
        "java/lang/reflect/Method.getParameterTypes()[Ljava/lang/Class;","java/lang/reflect/Method.getDeclaringClass()Ljava/lang/Class;",
        "java/lang/invoke/MethodType.methodType(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
        "java/lang/invoke/MethodType.parameterCount()I",
        "java/lang/invoke/MethodHandles.dropArguments(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
        "java/lang/invoke/MethodHandles$Lookup.findGetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
        "java/lang/invoke/MethodHandles$Lookup.findSetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
        "java/lang/invoke/MethodHandles$Lookup.findStaticGetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
        "java/lang/invoke/MethodHandles$Lookup.findStaticSetter(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
        "java/lang/invoke/MethodHandles$Lookup.findStatic(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
        "java/lang/invoke/MethodHandles$Lookup.findVirtual(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
        "java/lang/invoke/MethodHandles$Lookup.findSpecial(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;"
    );
    private ReValue invoke(MethodInsnNode call,ReValue receiver,List<? extends ReValue> args) {
        if(call.owner.equals("java/lang/Object"))return receiver.hasKnownValue()?classValue(receiver.type()):ObjectValue.VAL_CLASS;
        if(call.name.equals("toString"))return StringValue.VAL_STRING;
        if(call.owner.equals("java/lang/String")) {
            ReValue other=args.getFirst();if(other instanceof software.coley.recaf.util.analysis.eval.InstancedObjectValue<?> instance)other=instance.unmap();
            return other instanceof StringValue value && value.getText().isPresent()?IntValue.of(text(receiver).equals(value.getText().get())?1:0):IntValue.UNKNOWN;
        }
        if(call.owner.equals("java/lang/Class")) {
            if(call.name.equals("forName")) {
                String binaryName=text(args.getFirst());if(binaryName.indexOf('/')>=0)throw new IllegalArgumentException("Expected binary class name");
                String name=binaryName.replace('.','/');Type type=name.startsWith("[")?Type.getType(name):Type.getObjectType(name);
                if(type.getSort()==Type.ARRAY) {
                    Type element=type.getElementType();if(type.getDimensions()>255||element.getSort()==Type.VOID||!type.getDescriptor().equals(name))throw new IllegalArgumentException("Invalid array class name");
                    if(element.getSort()==Type.OBJECT)info(element);
                }
                if(type.getSort()==Type.OBJECT){info(type);initializationObligations.add(type.getInternalName());}
                return classValue(type);
            }
            Type type=((ClassValue)receiver).value;
            if(call.name.equals("getName"))return ObjectValue.string(type.getSort()==Type.ARRAY?type.getDescriptor().replace('/','.'):type.getClassName());
            if(type.getSort()!=Type.OBJECT)return switch(call.name) {
                case "getSuperclass"->type.getSort()==Type.ARRAY?classValue(Type.getObjectType("java/lang/Object")):ObjectValue.VAL_CLASS_NULL;
                case "getInterfaces"->array("java/lang/Class",type.getSort()==Type.ARRAY?List.of(classValue(Type.getObjectType("java/lang/Cloneable")),classValue(Type.getObjectType("java/io/Serializable"))):List.of());
                case "getDeclaredFields"->array("java/lang/reflect/Field",List.of());
                case "getDeclaredMethods"->array("java/lang/reflect/Method",List.of());
                default->throw new IllegalArgumentException("Unmodeled primitive/array metadata operation");
            };
            var owner=info(type);
            return switch(call.name) {
                case "getSuperclass" -> owner.getSuperName()==null || owner.hasInterfaceModifier()?ObjectValue.VAL_CLASS_NULL:classValue(Type.getObjectType(owner.getSuperName()));
                case "getInterfaces" -> array("java/lang/Class",owner.getInterfaces().stream().map(n->classValue(Type.getObjectType(n))).toList());
                case "getDeclaredFields" -> array("java/lang/reflect/Field",owner.getFields().stream().map(f->new MemberValue(owner.getName(),f.getName(),f.getDescriptor(),f.getAccess(),false)).toList());
                case "getDeclaredMethods" -> array("java/lang/reflect/Method",owner.getMethods().stream().filter(m->!m.getName().startsWith("<")).map(m->new MemberValue(owner.getName(),m.getName(),m.getDescriptor(),m.getAccess(),true)).toList());
                default -> throw new IllegalArgumentException("Unmodeled class operation");
            };
        }
        if(call.owner.startsWith("java/lang/reflect/")) {
            var member=(MemberValue)receiver;
            return switch(call.name) {
                case "getName"->ObjectValue.string(member.name);
                case "getDeclaringClass"->classValue(Type.getObjectType(member.owner));
                case "getType"->classValue(Type.getType(member.descriptor));
                case "getReturnType"->classValue(Type.getReturnType(member.descriptor));
                case "getParameterTypes"->array("java/lang/Class",Arrays.stream(Type.getArgumentTypes(member.descriptor)).map(this::classValue).toList());
                default->throw new IllegalArgumentException("Unmodeled reflection operation");
            };
        }
        if(call.owner.equals("java/lang/invoke/MethodType")) {
            if(call.name.equals("parameterCount"))return IntValue.of(((MethodTypeValue)receiver).value.getArgumentCount());
            return methodType(Type.getMethodType(((ClassValue)args.getFirst()).value,classTypes(args.get(1))));
        }
        if(call.owner.equals("java/lang/invoke/MethodHandles")) {
            var handle=(HandleValue)args.getFirst();int position=((IntValue)args.get(1)).value().orElseThrow();
            Type[] additions=classTypes(args.get(2));
            if(position!=handleType(handle.target).getArgumentCount()+handle.droppedArguments)throw new IllegalArgumentException("Only trailing metadata arguments are supported");
            return new HandleValue(handle.target,handle.droppedArguments+additions.length);
        }
        var lookup=(LookupValue)receiver;String owner=((ClassValue)args.get(0)).value.getInternalName();String name=text(args.get(1));
        boolean field=call.name.contains("Getter") || call.name.contains("Setter");
        String descriptor=field?((ClassValue)args.get(2)).value.getDescriptor():((MethodTypeValue)args.get(2)).value.getDescriptor();
        MemberValue member=findMember(owner,name,descriptor,!field,new HashSet<>());
        if(member==null || !accessible(lookup.caller,member))throw new IllegalArgumentException("Unresolved or inaccessible member");
        if(name.startsWith("<") || call.name.contains("Setter")&&(member.access&ACC_FINAL)!=0)throw new IllegalArgumentException("Unsupported special/final member access");
        boolean isStatic=(member.access&ACC_STATIC)!=0;
        if(isStatic!=call.name.contains("Static"))throw new IllegalArgumentException("Static/instance mismatch");
        int tag=switch(call.name) {
            case "findGetter"->H_GETFIELD;case "findSetter"->H_PUTFIELD;case "findStaticGetter"->H_GETSTATIC;case "findStaticSetter"->H_PUTSTATIC;
            case "findStatic"->H_INVOKESTATIC;case "findSpecial"->H_INVOKESPECIAL;
            case "findVirtual"->info(Type.getObjectType(member.owner)).hasInterfaceModifier()?H_INVOKEINTERFACE:H_INVOKEVIRTUAL;
            default->throw new IllegalArgumentException("Unmodeled lookup");
        };
        if(tag==H_INVOKESPECIAL && (!((ClassValue)args.get(3)).value.getInternalName().equals(lookup.caller)
                || !member.owner.equals(lookup.caller) || (member.access&ACC_PRIVATE)==0))throw new IllegalArgumentException("Special dispatch outside exact private owner retained");
        Handle target=new Handle(tag,member.owner,member.name,member.descriptor,info(Type.getObjectType(member.owner)).hasInterfaceModifier());
        resolutions.add(target);return new HandleValue(target,0);
    }
    private JvmClassInfo info(Type type) {
        var path=workspace.findJvmClass(true,type.getInternalName());if(path==null)throw new IllegalArgumentException("Missing class metadata: "+type);return path.getValue().asJvmClass();
    }
    private MemberValue findMember(String owner,String name,String descriptor,boolean method,Set<String> seen) {
        if(!seen.add(owner))return null;var cls=info(Type.getObjectType(owner));
        var found=method?cls.getDeclaredMethod(name,descriptor):cls.getDeclaredField(name,descriptor);
        if(found!=null)return new MemberValue(owner,name,descriptor,found.getAccess(),method);
        // ZKM supplies the declaring class obtained from Field/Method metadata. Keep
        // inherited/default-method dispatch unresolved instead of guessing its precedence.
        return null;
    }
    private boolean accessible(String caller,MemberValue member) {
        var owner=info(Type.getObjectType(member.owner));boolean samePackage=packageName(caller).equals(packageName(member.owner));
        if(!owner.hasPublicModifier() && !samePackage)return false;
        return (member.access&ACC_PUBLIC)!=0 || (member.access&ACC_PRIVATE)!=0 && caller.equals(member.owner)
            || (member.access&(ACC_PUBLIC|ACC_PRIVATE))==0 && samePackage;
    }
    private static String packageName(String name){int split=name.lastIndexOf('/');return split<0?"":name.substring(0,split);}
    private static ArrayValue array(String element,List<? extends ReValue> values){return new ArrayValueImpl(Type.getType("[L"+element+";"),Nullness.NOT_NULL,values.size(),values::get);}
    private static Type[] classTypes(ReValue value) {
        var array=(ArrayValue)value;int length=array.getFirstDimensionLength().orElseThrow();if(length>255)throw new IllegalArgumentException("Oversized type vector");
        Type[] types=new Type[length];for(int i=0;i<length;i++)types[i]=((ClassValue)array.getValue(i)).value;return types;
    }
    private static String text(ReValue value){if(value instanceof software.coley.recaf.util.analysis.eval.InstancedObjectValue<?> instance)value=instance.unmap();return ((StringValue)value).getText().orElseThrow();}
    private static ReValue unknown(Type type){try{return ReValue.ofType(type,Nullness.UNKNOWN);}catch(Exception invalid){throw new IllegalArgumentException(invalid);}}
    private static Type handleType(Handle handle) {
        Type owner=Type.getObjectType(handle.getOwner());
        return switch(handle.getTag()) {
            case H_GETSTATIC->Type.getMethodType(Type.getType(handle.getDesc()));case H_PUTSTATIC->Type.getMethodType(Type.VOID_TYPE,Type.getType(handle.getDesc()));
            case H_GETFIELD->Type.getMethodType(Type.getType(handle.getDesc()),owner);case H_PUTFIELD->Type.getMethodType(Type.VOID_TYPE,owner,Type.getType(handle.getDesc()));
            case H_INVOKESTATIC->Type.getMethodType(handle.getDesc());
            default->{var args=new ArrayList<Type>();args.add(owner);args.addAll(List.of(Type.getArgumentTypes(handle.getDesc())));yield Type.getMethodType(Type.getReturnType(handle.getDesc()),args.toArray(Type[]::new));}
        };
    }
    public static abstract class MetadataValue extends ObjectValueImpl {
        MetadataValue(String type){super(Type.getObjectType(type),Nullness.NOT_NULL);}
        @Override public boolean hasKnownValue(){return true;}
        @Override public boolean equals(Object other){return this==other;}
        @Override public int hashCode(){return System.identityHashCode(this);}
    }
    public static final class ClassValue extends MetadataValue {public final Type value;ClassValue(Type value){super("java/lang/Class");this.value=value;}}
    public static final class MethodTypeValue extends MetadataValue {public final Type value;MethodTypeValue(Type value){super("java/lang/invoke/MethodType");this.value=value;}}
    public static final class LookupValue extends MetadataValue {public final String caller;LookupValue(String caller){super("java/lang/invoke/MethodHandles$Lookup");this.caller=caller;}}
    public static final class HandleValue extends MetadataValue {public final Handle target;public final int droppedArguments;HandleValue(Handle target,int dropped){super("java/lang/invoke/MethodHandle");this.target=target;this.droppedArguments=dropped;}}
    public static final class MemberValue extends MetadataValue {
        public final String owner,name,descriptor;public final int access;
        MemberValue(String owner,String name,String descriptor,int access,boolean method){super(method?"java/lang/reflect/Method":"java/lang/reflect/Field");this.owner=owner;this.name=name;this.descriptor=descriptor;this.access=access;}
    }
}
