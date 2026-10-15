package recafx.deobf.ported;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Instruction helpers shared by the transformers ported from narumii/Deobfuscator (GPL-3.0).
 */
public final class PortedUtil {
	private PortedUtil() {}

	public static boolean isIntConst(AbstractInsnNode insn) {
		if (insn == null) return false;
		int op = insn.getOpcode();
		return (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
				|| (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer);
	}

	public static int intValue(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (insn instanceof IntInsnNode iin) return iin.operand;
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) return i;
		throw new IllegalArgumentException("Not an int constant: " + op);
	}

	public static boolean isNumberConst(AbstractInsnNode insn) {
		if (isIntConst(insn)) return true;
		int op = insn.getOpcode();
		return op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1 || op == Opcodes.FCONST_0 || op == Opcodes.FCONST_1
				|| op == Opcodes.FCONST_2 || op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1
				|| (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number);
	}

	public static Number numberValue(AbstractInsnNode insn) {
		if (isIntConst(insn)) return intValue(insn);
		int op = insn.getOpcode();
		if (op == Opcodes.LCONST_0) return 0L;
		if (op == Opcodes.LCONST_1) return 1L;
		if (op == Opcodes.FCONST_0) return 0f;
		if (op == Opcodes.FCONST_1) return 1f;
		if (op == Opcodes.FCONST_2) return 2f;
		if (op == Opcodes.DCONST_0) return 0d;
		if (op == Opcodes.DCONST_1) return 1d;
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number n) return n;
		throw new IllegalArgumentException("Not a number constant");
	}

	public static boolean isLongConst(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		return op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1 || (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long);
	}

	public static long longValue(AbstractInsnNode insn) {
		return numberValue(insn).longValue();
	}

	/** Next instruction with a real opcode (skips labels, frames, line numbers). */
	public static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode cur = insn == null ? null : insn.getNext();
		while (cur != null && cur.getOpcode() < 0) cur = cur.getNext();
		return cur;
	}

	public static AbstractInsnNode next(AbstractInsnNode insn, int n) {
		AbstractInsnNode cur = insn;
		for (int i = 0; i < n && cur != null; i++) cur = next(cur);
		return cur;
	}

	public static AbstractInsnNode prev(AbstractInsnNode insn) {
		AbstractInsnNode cur = insn == null ? null : insn.getPrevious();
		while (cur != null && cur.getOpcode() < 0) cur = cur.getPrevious();
		return cur;
	}

	public static AbstractInsnNode first(MethodNode method) {
		if (method.instructions == null || method.instructions.size() == 0) return null;
		AbstractInsnNode cur = method.instructions.getFirst();
		while (cur != null && cur.getOpcode() < 0) cur = cur.getNext();
		return cur;
	}

	public static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		if (method.instructions == null) return out;
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	public static boolean isMath(int op) {
		return op >= Opcodes.IADD && op <= Opcodes.LXOR;
	}

	public static int countMath(MethodNode method) {
		int n = 0;
		for (AbstractInsnNode insn : real(method)) if (isMath(insn.getOpcode())) n++;
		return n;
	}

	public static MethodNode findMethod(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
		return null;
	}

	public static MethodNode findClinit(ClassNode node) {
		return findMethod(node, "<clinit>", "()V");
	}

	public static AbstractInsnNode intInsn(int value) {
		if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
		if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, value);
		if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, value);
		return new LdcInsnNode(value);
	}

	/** Decrypted strings must look like text, otherwise the key extraction picked the wrong constants. */
	public static boolean looksLikeText(String s) {
		if (s == null) return false;
		if (s.isEmpty()) return true;
		int ok = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c >= 0x20 && c < 0x7f) || c == '\n' || c == '\r' || c == '\t' || Character.isLetterOrDigit(c)) ok++;
		}
		return ok * 100 / s.length() >= 90;
	}

	public static void removeAll(InsnList list, AbstractInsnNode... insns) {
		for (AbstractInsnNode insn : insns) if (insn != null && list.contains(insn)) list.remove(insn);
	}
}
