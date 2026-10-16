package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/** Bounded, modular arithmetic over immutable input expressions. No target code is invoked. */
public final class MixedBooleanArithmeticTransformer extends LocalRewriteTransformer implements Opcodes {
    @Override public String identifier() { return "lightning.flow.mixed-boolean-arithmetic"; }

    @Override public int rewrite(ClassNode owner) {
        int changes = 0;
        for (MethodNode method : owner.methods) {
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0 || method.instructions.size() > 12000
                    || method.maxLocals > 512 || method.maxStack > 512 || method.tryCatchBlocks.size() > 128) continue;
            Frame<BasicValue>[] frames;
            try {
                new Analyzer<>(new BasicVerifier()).analyze(owner.name,method);
                frames = new Analyzer<>(new Algebra()).analyze(owner.name,method);
            } catch (AnalyzerException | IllegalArgumentException invalid) { continue; }
            AbstractInsnNode[] code = method.instructions.toArray();
            for (int i=0;i<code.length;i++) {
                if (!binary(code[i].getOpcode()) || frames[i] == null || frames[i].getStackSize()<2) continue;
                Frame<BasicValue> frame = frames[i];
                Poly a=poly(frame.getStack(frame.getStackSize()-2)), b=poly(frame.getStack(frame.getStackSize()-1));
                if (a==null || b==null || a.fixed() && b.fixed()) continue;
                Poly result=calculate(code[i].getOpcode(),a,b);
                if (result==null || !result.fixed()) continue;
                // Evaluate every original operand, including exceptions and side effects.
                method.instructions.insertBefore(code[i],new InsnNode(POP2));
                if (result.wide) method.instructions.insertBefore(code[i],new InsnNode(POP2));
                Object value = result.wide ? (Object)Long.valueOf(result.constant) : Integer.valueOf((int)result.constant);
                method.instructions.set(code[i],new LdcInsnNode(value));
                changes++;
            }
        }
        return changes;
    }
    private static boolean binary(int op) {
        return switch(op) { case IADD,LADD,ISUB,LSUB,IMUL,LMUL,IAND,LAND,IOR,LOR,IXOR,LXOR -> true; default -> false; };
    }
    private static Poly poly(BasicValue value) { return value instanceof Symbolic s ? s.value : null; }
    private static long wrap(long value,boolean wide) { return wide ? value : (int)value; }
    private record Poly(boolean wide,long constant,SortedMap<String,Long> terms) {
        boolean fixed() { return terms.isEmpty(); }
    }
    private static Poly constant(boolean wide,long value) { return new Poly(wide,wrap(value,wide),Collections.emptySortedMap()); }
    private static Poly atom(boolean wide,String key) {
        if(key.length()>2048)return null;
        TreeMap<String,Long> terms=new TreeMap<>();terms.put(key,1L);
        return new Poly(wide,0,Collections.unmodifiableSortedMap(terms));
    }
    private static Poly scale(Poly value,long factor) {
        if(value==null)return null;
        TreeMap<String,Long> terms=new TreeMap<>();
        value.terms.forEach((key,coefficient)->{long scaled=wrap(coefficient*factor,value.wide);if(scaled!=0)terms.put(key,scaled);});
        return new Poly(value.wide,wrap(value.constant*factor,value.wide),Collections.unmodifiableSortedMap(terms));
    }
    private static Poly add(Poly a,Poly b) {
        if(a==null || b==null || a.wide!=b.wide)return null;
        TreeMap<String,Long> terms=new TreeMap<>(a.terms);
        b.terms.forEach((key,coefficient)->{long sum=wrap(terms.getOrDefault(key,0L)+coefficient,a.wide);if(sum==0)terms.remove(key);else terms.put(key,sum);});
        if(terms.size()>32)return null;
        return new Poly(a.wide,wrap(a.constant+b.constant,a.wide),Collections.unmodifiableSortedMap(terms));
    }
    private static Poly and(Poly a,Poly b) {
        if(a.equals(b))return a;
        if(a.fixed()) {
            if(a.constant==0)return a;
            if(a.constant==-1)return b;
        }
        if(b.fixed()) {
            if(b.constant==0)return b;
            if(b.constant==-1)return a;
        }
        String left=a.toString(),right=b.toString();
        if(left.length()+right.length()>2000)return null;
        if(left.compareTo(right)>0){String swap=left;left=right;right=swap;}
        return atom(a.wide,"and("+left+","+right+")");
    }
    private static Poly calculate(int op,Poly a,Poly b) {
        if(a==null || b==null || a.wide!=b.wide)return null;
        if(a.fixed() && b.fixed())return constant(a.wide,switch(op){
            case IADD,LADD -> a.constant+b.constant; case ISUB,LSUB -> a.constant-b.constant;
            case IMUL,LMUL -> a.constant*b.constant; case IAND,LAND -> a.constant&b.constant;
            case IOR,LOR -> a.constant|b.constant; case IXOR,LXOR -> a.constant^b.constant;
            default -> throw new IllegalArgumentException();
        });
        return switch(op) {
            case IADD,LADD -> add(a,b);
            case ISUB,LSUB -> add(a,scale(b,-1));
            case IMUL,LMUL -> a.fixed()?scale(b,a.constant):b.fixed()?scale(a,b.constant):null;
            case IAND,LAND -> and(a,b);
            case IOR,LOR -> add(add(a,b),scale(and(a,b),-1));
            case IXOR,LXOR -> add(add(a,b),scale(and(a,b),-2));
            default -> null;
        };
    }
    private static final class Symbolic extends BasicValue {
        final Poly value;
        Symbolic(Poly value){this(value.wide,value);}
        Symbolic(boolean wide,Poly value){super(wide?Type.LONG_TYPE:Type.INT_TYPE);this.value=value;}
        @Override public boolean equals(Object other){return other instanceof Symbolic s&&getType().equals(s.getType())&&Objects.equals(value,s.value);}
        @Override public int hashCode(){return Objects.hash(getType(),value);}
    }
    private static BasicValue unknown(BasicValue base) {
        if(base==null)return null;
        if(Type.INT_TYPE.equals(base.getType()))return new Symbolic(false,null);
        if(Type.LONG_TYPE.equals(base.getType()))return new Symbolic(true,null);
        return base;
    }
    private static BasicValue value(Poly poly,BasicValue fallback) { return poly==null?unknown(fallback):new Symbolic(poly); }
    private static final class Algebra extends BasicInterpreter {
        private int steps;
        Algebra(){super(ASM9);}
        private void budget(AbstractInsnNode insn)throws AnalyzerException {if(++steps>100000)throw new AnalyzerException(insn,"Arithmetic analysis budget");}
        @Override public BasicValue newValue(Type type) { return unknown(super.newValue(type)); }
        @Override public BasicValue newParameterValue(boolean instance,int local,Type type) {
            BasicValue base=super.newParameterValue(instance,local,type);
            if(BasicValue.INT_VALUE.equals(base) || BasicValue.LONG_VALUE.equals(base))
                return value(atom(BasicValue.LONG_VALUE.equals(base),"arg"+local),base);
            return base;
        }
        @Override public BasicValue newOperation(AbstractInsnNode insn)throws AnalyzerException {
            budget(insn); int op=insn.getOpcode();
            if(op>=ICONST_M1 && op<=ICONST_5)return new Symbolic(constant(false,op-ICONST_0));
            if(op==LCONST_0 || op==LCONST_1)return new Symbolic(constant(true,op-LCONST_0));
            if(insn instanceof IntInsnNode n && (op==BIPUSH || op==SIPUSH))return new Symbolic(constant(false,n.operand));
            if(insn instanceof LdcInsnNode n) {
                if(n.cst instanceof Integer x)return new Symbolic(constant(false,x));
                if(n.cst instanceof Long x)return new Symbolic(constant(true,x));
            }
            return unknown(super.newOperation(insn));
        }
        @Override public BasicValue copyOperation(AbstractInsnNode insn,BasicValue value)throws AnalyzerException {budget(insn);return value;}
        @Override public BasicValue unaryOperation(AbstractInsnNode insn,BasicValue input)throws AnalyzerException {
            budget(insn);Poly a=poly(input);BasicValue base=unknown(super.unaryOperation(insn,input));
            if(a==null)return base;
            if(insn.getOpcode()==INEG || insn.getOpcode()==LNEG)return value(scale(a,-1),base);
            if(insn instanceof IincInsnNode inc)return value(add(a,constant(false,inc.incr)),base);
            return base;
        }
        @Override public BasicValue binaryOperation(AbstractInsnNode insn,BasicValue a,BasicValue b)throws AnalyzerException {
            budget(insn);BasicValue base=unknown(super.binaryOperation(insn,a,b));
            return binary(insn.getOpcode())?value(calculate(insn.getOpcode(),poly(a),poly(b)),base):base;
        }
        @Override public BasicValue naryOperation(AbstractInsnNode insn,List<? extends BasicValue> args)throws AnalyzerException {budget(insn);return unknown(super.naryOperation(insn,args));}
        @Override public BasicValue merge(BasicValue a,BasicValue b) {
            if(a.equals(b))return a;
            // BasicValue's equals accepts subclasses: strip symbols on both sides before merging.
            BasicValue x=a instanceof Symbolic?new BasicValue(a.getType()):a;
            BasicValue y=b instanceof Symbolic?new BasicValue(b.getType()):b;
            return unknown(super.merge(x,y));
        }
    }
}
