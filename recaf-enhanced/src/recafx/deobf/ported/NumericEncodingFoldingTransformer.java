/* SPDX-License-Identifier: GPL-3.0-or-later
 * Adapted numeric encodings from Diobfuscator v1 CaesiumNumberTransformer and BinsecureNumberTransformer.
 * Copyright uwu.narumi contributors. See THIRD-PARTY-NOTICES.txt.
 * LightningRecaf: exact opcodes/types, block boundaries, correct int result for floatToIntBits,
 * raw-bit variants, and no payload-changing NaN conversion.
 */
package recafx.deobf.ported;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.transform.*;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

public final class NumericEncodingFoldingTransformer implements JvmClassTransformer, Opcodes {
    @Override public String identifier(){return "lightning.caesium-binsecure.numeric-encoding";}
    @Override public void transform(JvmTransformerContext context,Workspace workspace,WorkspaceResource resource,JvmClassBundle bundle,JvmClassInfo info) {
        ClassNode node=context.getNode(bundle,info);
        if(rewrite(node)>0){context.setRecomputeFrames(node.name);context.setNode(bundle,info,node);}
    }
    public static int rewrite(ClassNode node) {
        int changed=0;
        for(MethodNode method:node.methods)for(AbstractInsnNode insn:method.instructions.toArray()) {
            AbstractInsnNode previous=insn.getPrevious();
            if(previous==null || !PortedUtil.isNumberConst(previous))continue;
            Number input=PortedUtil.numberValue(previous), output=null;
            if(insn.getOpcode()==L2I && input instanceof Long)output=input.intValue();
            if(insn instanceof MethodInsnNode call && call.getOpcode()==INVOKESTATIC && !call.itf) {
                output=switch(call.owner+'.'+call.name+call.desc) {
                    case "java/lang/Integer.reverse(I)I" -> input instanceof Integer?Integer.reverse(input.intValue()):null;
                    case "java/lang/Integer.reverseBytes(I)I" -> input instanceof Integer?Integer.reverseBytes(input.intValue()):null;
                    case "java/lang/Long.reverse(J)J" -> input instanceof Long?Long.reverse(input.longValue()):null;
                    case "java/lang/Long.reverseBytes(J)J" -> input instanceof Long?Long.reverseBytes(input.longValue()):null;
                    case "java/lang/Float.floatToIntBits(F)I" -> input instanceof Float?Float.floatToIntBits(input.floatValue()):null;
                    case "java/lang/Float.floatToRawIntBits(F)I" -> input instanceof Float?Float.floatToRawIntBits(input.floatValue()):null;
                    case "java/lang/Double.doubleToLongBits(D)J" -> input instanceof Double?Double.doubleToLongBits(input.doubleValue()):null;
                    case "java/lang/Double.doubleToRawLongBits(D)J" -> input instanceof Double?Double.doubleToRawLongBits(input.doubleValue()):null;
                    case "java/lang/Float.intBitsToFloat(I)F" -> input instanceof Integer && !Float.isNaN(Float.intBitsToFloat(input.intValue()))?Float.intBitsToFloat(input.intValue()):null;
                    case "java/lang/Double.longBitsToDouble(J)D" -> input instanceof Long && !Double.isNaN(Double.longBitsToDouble(input.longValue()))?Double.longBitsToDouble(input.longValue()):null;
                    default -> null;
                };
            }
            if(output==null)continue;
            method.instructions.remove(previous);method.instructions.set(insn,new LdcInsnNode(output));changed++;
        }
        return changed;
    }
}
