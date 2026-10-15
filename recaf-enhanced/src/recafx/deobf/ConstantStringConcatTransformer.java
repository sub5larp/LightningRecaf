package recafx.deobf;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;

/** Resolves only the standard JDK string-concat bootstrap with contiguous literal arguments. */
public final class ConstantStringConcatTransformer extends LocalRewriteTransformer implements Opcodes {
    @Override public String identifier() { return "lightning.strings.constant-indy-concat"; }
    @Override public int rewrite(ClassNode node) {
        int changes=0;
        for(MethodNode method:node.methods) for(AbstractInsnNode insn:method.instructions.toArray()) {
            if(!(insn instanceof InvokeDynamicInsnNode call) || !call.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory") || call.bsm.getTag()!=H_INVOKESTATIC
                || !Type.getReturnType(call.desc).equals(Type.getType(String.class)))continue;
            boolean recipe=call.bsm.getName().equals("makeConcatWithConstants");
            if(!recipe && !call.bsm.getName().equals("makeConcat"))continue;
            String bootstrapDesc="(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                +(recipe?"Ljava/lang/String;[Ljava/lang/Object;":"")+")Ljava/lang/invoke/CallSite;";
            if(!call.bsm.getDesc().equals(bootstrapDesc))continue;
            if(recipe && (call.bsmArgs.length==0 || !(call.bsmArgs[0] instanceof String)))continue;
            Type[] types=Type.getArgumentTypes(call.desc);
            String[] values=new String[types.length];
            AbstractInsnNode first=call;
            boolean valid=true;
            for(int i=types.length-1;i>=0;i--) {
                first=first.getPrevious();
                String value=literal(first,types[i]);
                if(value==null) {valid=false;break;}values[i]=value;
            }
            if(!valid)continue;
            StringBuilder text=new StringBuilder();int arg=0,constant=1;
            if(recipe) {
                for(char token:((String)call.bsmArgs[0]).toCharArray()) {
                    if(token==1) {if(arg==values.length){valid=false;break;} text.append(values[arg++]);}
                    else if(token==2) {
                        if(constant==call.bsmArgs.length){valid=false;break;}
                        Object value=call.bsmArgs[constant++];
                        if(!(value instanceof String || value instanceof Number)){valid=false;break;}
                        text.append(value);
                    } else text.append(token);
                    if(text.length()>20000){valid=false;break;}
                }
                valid &= arg==values.length && constant==call.bsmArgs.length;
            } else { for(String value:values)text.append(value); valid &= call.bsmArgs.length==0; }
            if(!valid || text.length()>20000)continue; // bounded below the modified UTF-8 constant limit
            method.instructions.insertBefore(first,new LdcInsnNode(text.toString()));
            for(AbstractInsnNode n=first;n!=call;) {AbstractInsnNode next=n.getNext();method.instructions.remove(n);n=next;}
            method.instructions.remove(call);changes++;
        }
        return changes;
    }
    private static String literal(AbstractInsnNode insn,Type type) {
        if(insn==null)return null;Object value;
        if(insn instanceof LdcInsnNode ldc)value=ldc.cst;
        else if(insn.getOpcode()==ACONST_NULL && type.getSort()>=Type.ARRAY)return "null";
        else if(insn.getOpcode()>=ICONST_M1 && insn.getOpcode()<=ICONST_5)value=insn.getOpcode()-ICONST_0;
        else if(insn instanceof IntInsnNode i && (i.getOpcode()==BIPUSH||i.getOpcode()==SIPUSH))value=i.operand;
        else if(insn.getOpcode()==LCONST_0 || insn.getOpcode()==LCONST_1)value=(long)(insn.getOpcode()-LCONST_0);
        else return null;
        return switch(type.getSort()) {
            case Type.BOOLEAN -> value instanceof Integer n && (n==0 || n==1) ? Boolean.toString(n!=0) : null;
            case Type.CHAR -> value instanceof Integer n ? Character.toString((char)n.intValue()) : null;
            case Type.BYTE -> value instanceof Integer n ? Byte.toString(n.byteValue()) : null;
            case Type.SHORT -> value instanceof Integer n ? Short.toString(n.shortValue()) : null;
            case Type.INT -> value instanceof Integer ? value.toString() : null;
            case Type.LONG -> value instanceof Long ? value.toString() : null;
            case Type.FLOAT -> value instanceof Float ? value.toString() : null;
            case Type.DOUBLE -> value instanceof Double ? value.toString() : null;
            case Type.OBJECT -> value instanceof String && (type.getInternalName().equals("java/lang/String")||type.getInternalName().equals("java/lang/Object")) ? (String)value : null;
            default -> null;
        };
    }
}
