package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/** Original automatic predicate recovery. Tracks known bits, signed ranges and a
 * bounded polynomial over GF(2). Operand instructions remain intact, including
 * exceptions, volatile reads and calls with side effects. */
public final class IntegerDomainTransformer extends LocalRewriteTransformer implements Opcodes {
    @Override public String identifier(){return "lightning.flow.integer-domain";}
    @Override public int rewrite(ClassNode owner) {
        int changes=0;
        for(var method:owner.methods) {
            if((method.access&(ACC_NATIVE|ACC_ABSTRACT))!=0 || method.instructions.size()>12000)continue;
            Frame<BasicValue>[] frames;
            try{frames=new Analyzer<>(new Domains()).analyze(owner.name,method);}catch(Exception invalid){continue;}
            var instructions=method.instructions.toArray();
            for(int i=0;i<instructions.length;i++) {
                var insn=instructions[i];var frame=frames[i];if(frame==null)continue;int op=insn.getOpcode();
                if(insn instanceof JumpInsnNode jump && op>=IFEQ && op<=IF_ICMPLE) {
                    int count=op<=IFLE?1:2;if(frame.getStackSize()<count)continue;
                    var a=domain(frame.getStack(frame.getStackSize()-count));var b=count==1?constant(0):domain(frame.getStack(frame.getStackSize()-1));
                    Boolean taken=condition(op,a,b);if(taken==null)continue;
                    method.instructions.insertBefore(jump,new InsnNode(count==1?POP:POP2));
                    if(taken)method.instructions.set(jump,new JumpInsnNode(GOTO,jump.label));else method.instructions.remove(jump);changes++;
                } else if(binary(op) && frame.getStackSize()>=2) {
                    var a=domain(frame.getStack(frame.getStackSize()-2));var b=domain(frame.getStack(frame.getStackSize()-1));
                    if(a==null||b==null||a.fixed()&&b.fixed())continue;var value=combine(op,a,b);
                    if(value.fixed()){method.instructions.insertBefore(insn,new InsnNode(POP2));method.instructions.set(insn,new LdcInsnNode(value.one));changes++;}
                }
            }
        }
        return changes;
    }
    private static Domain domain(BasicValue v){return v instanceof Domain d?d:v!=null&&v.equals(BasicValue.INT_VALUE)?UNKNOWN:null;}
    private static boolean binary(int op){return switch(op){case IADD,ISUB,IMUL,IAND,IOR,IXOR,ISHL,ISHR,IUSHR->true;default->false;};}
    private static final Domain UNKNOWN=new Domain(0,0,null,null);
    private static Domain constant(int value){return new Domain(~value,value,(value&1)==0?Set.of():Set.of(0L),new Expression(-1,null,null,value,1));}
    private static Boolean condition(int op,Domain a,Domain b) {
        if(a==null||b==null)return null;
        boolean equal=a.expression!=null&&a.expression.equals(b.expression);
        boolean disjoint=((a.one&b.zero)|(a.zero&b.one))!=0;
        int relation=op<=IFLE?op-IFEQ:op-IF_ICMPEQ;
        return switch(relation) {
            case 0->{if(equal)yield true;if(a.fixed()&&b.fixed())yield a.one==b.one;if(disjoint)yield false;yield null;}
            case 1->{if(equal)yield false;if(disjoint)yield true;if(a.fixed()&&b.fixed())yield a.one!=b.one;yield null;}
            case 2->{if(equal)yield false;if(a.maximum()<b.minimum())yield true;if(a.minimum()>=b.maximum())yield false;yield null;}
            case 3->{if(equal)yield true;if(a.minimum()>=b.maximum())yield true;if(a.maximum()<b.minimum())yield false;yield null;}
            case 4->{if(equal)yield false;if(a.minimum()>b.maximum())yield true;if(a.maximum()<=b.minimum())yield false;yield null;}
            case 5->{if(equal)yield true;if(a.maximum()<=b.minimum())yield true;if(a.minimum()>b.maximum())yield false;yield null;}
            default->null;
        };
    }
    private static Domain combine(int op,Domain a,Domain b) {
        Expression expression=expression(op,a.expression,b.expression,0);
        if(a.expression!=null&&a.expression.equals(b.expression)) {
            if(op==ISUB||op==IXOR)return constant(0);
            if(op==IAND||op==IOR)return a;
        }
        if(a.fixed()&&b.fixed())return constant(switch(op){case IADD->a.one+b.one;case ISUB->a.one-b.one;case IMUL->a.one*b.one;case IAND->a.one&b.one;case IOR->a.one|b.one;case IXOR->a.one^b.one;case ISHL->a.one<<b.one;case ISHR->a.one>>b.one;case IUSHR->a.one>>>b.one;default->throw new IllegalArgumentException();});
        int zero=0,one=0;Set<Long> parity=null;
        switch(op) {
            case IAND->{zero=a.zero|b.zero;one=a.one&b.one;parity=multiply(a.parity,b.parity);}
            case IOR->{zero=a.zero&b.zero;one=a.one|b.one;parity=add(add(a.parity,b.parity),multiply(a.parity,b.parity));}
            case IXOR->{zero=a.zero&b.zero|a.one&b.one;one=a.zero&b.one|a.one&b.zero;parity=add(a.parity,b.parity);}
            case IADD,ISUB,IMUL->{
                int bits=Math.min(Integer.numberOfTrailingZeros(~(a.zero|a.one)),Integer.numberOfTrailingZeros(~(b.zero|b.one)));
                int mask=bits==32?-1:(1<<bits)-1;int low=op==IADD?a.one+b.one:op==ISUB?a.one-b.one:a.one*b.one;one=low&mask;zero=~low&mask;
                parity=op==IMUL?multiply(a.parity,b.parity):add(a.parity,b.parity);
            }
            case ISHL,ISHR,IUSHR->{if(b.fixed()) {
                int distance=b.one&31;
                if(distance==0)return a;
                if(op==ISHL){one=a.one<<distance;zero=a.zero<<distance|(-1>>>(32-distance));parity=Set.of();}
                else {one=a.one>>>distance;zero=a.zero>>>distance;int high=-1<<(32-distance);if(op==IUSHR||(a.zero&Integer.MIN_VALUE)!=0)zero|=high;else if((a.one&Integer.MIN_VALUE)!=0)one|=high;}
            }}
            default->throw new IllegalArgumentException();
        }
        return new Domain(zero,one,parity,expression);
    }
    // A Boolean polynomial uses XOR for addition, AND for multiplication and x*x=x.
    // A monomial is a bit-set of immutable method-entry integer parameters.
    private static Set<Long> add(Set<Long> a,Set<Long> b) {
        if(a==null||b==null)return null;var out=new HashSet<>(a);for(long term:b)if(!out.add(term))out.remove(term);return out.size()>64?null:Set.copyOf(out);
    }
    private static Set<Long> multiply(Set<Long> a,Set<Long> b) {
        if(a!=null&&a.isEmpty()||b!=null&&b.isEmpty())return Set.of();
        if(a==null||b==null||a.size()*b.size()>256)return null;var out=new HashSet<Long>();for(long x:a)for(long y:b){long term=x|y;if(!out.add(term))out.remove(term);}return out.size()>64?null:Set.copyOf(out);
    }
    private record Expression(int op,Expression left,Expression right,int value,int size){}
    private static Expression expression(int op,Expression a,Expression b,int value) {
        if(a==null||b==null)return null;int size=a.size+b.size+1;return size>24?null:new Expression(op,a,b,value,size);
    }
    private static final class Domain extends BasicValue {
        final int zero,one;final Set<Long> parity;final Expression expression;
        Domain(int zero,int one,Set<Long> parity,Expression expression) {
            super(Type.INT_TYPE);
            if(parity!=null&&parity.isEmpty())zero|=1;else if(parity!=null&&parity.equals(Set.of(0L)))one|=1;
            this.zero=zero;this.one=one;this.parity=parity;this.expression=expression;
        }
        boolean fixed(){return (zero|one)==-1;}
        int minimum(){return (zero&Integer.MIN_VALUE)==0?one|Integer.MIN_VALUE:one;}
        int maximum(){return (one&Integer.MIN_VALUE)==0?~zero&Integer.MAX_VALUE:~zero;}
        @Override public boolean equals(Object other){return other instanceof Domain d&&zero==d.zero&&one==d.one&&Objects.equals(parity,d.parity)&&Objects.equals(expression,d.expression);}
        @Override public int hashCode(){return Objects.hash(zero,one,parity,expression);}
    }
    private static final class Domains extends BasicInterpreter {
        private int steps;
        Domains(){super(ASM9);}
        private void budget(AbstractInsnNode i)throws AnalyzerException{if(++steps>100000)throw new AnalyzerException(i,"Integer-domain analysis budget");}
        @Override public BasicValue newValue(Type type){var base=super.newValue(type);return BasicValue.INT_VALUE.equals(base)?UNKNOWN:base;}
        @Override public BasicValue newParameterValue(boolean instance,int local,Type type){var base=newValue(type);return base instanceof Domain&&local<60?new Domain(0,0,Set.of(1L<<local),new Expression(-2,null,null,local,1)):base;}
        @Override public BasicValue newOperation(AbstractInsnNode insn)throws AnalyzerException {
            budget(insn);int op=insn.getOpcode();if(op>=ICONST_M1&&op<=ICONST_5)return constant(op-ICONST_0);
            if(insn instanceof IntInsnNode n&&(op==BIPUSH||op==SIPUSH))return constant(n.operand);
            if(insn instanceof LdcInsnNode l&&l.cst instanceof Integer n)return constant(n);
            var base=super.newOperation(insn);return BasicValue.INT_VALUE.equals(base)?UNKNOWN:base;
        }
        @Override public BasicValue copyOperation(AbstractInsnNode insn,BasicValue value)throws AnalyzerException{budget(insn);return value;}
        @Override public BasicValue unaryOperation(AbstractInsnNode insn,BasicValue value)throws AnalyzerException {
            budget(insn);var d=domain(value);
            if(d!=null) {
                if(insn.getOpcode()==INEG)return combine(ISUB,constant(0),d);
                if(insn instanceof IincInsnNode inc)return combine(IADD,d,constant(inc.incr));
                if(insn.getOpcode()==I2C)return combine(IAND,d,constant(65535));
            }
            var base=super.unaryOperation(insn,value);return BasicValue.INT_VALUE.equals(base)?UNKNOWN:base;
        }
        @Override public BasicValue binaryOperation(AbstractInsnNode insn,BasicValue left,BasicValue right)throws AnalyzerException {
            budget(insn);var a=domain(left);var b=domain(right);if(binary(insn.getOpcode())&&a!=null&&b!=null)return combine(insn.getOpcode(),a,b);
            var base=super.binaryOperation(insn,left,right);return BasicValue.INT_VALUE.equals(base)?UNKNOWN:base;
        }
        @Override public BasicValue naryOperation(AbstractInsnNode insn,List<? extends BasicValue> values)throws AnalyzerException{budget(insn);var base=super.naryOperation(insn,values);return BasicValue.INT_VALUE.equals(base)?UNKNOWN:base;}
        @Override public BasicValue merge(BasicValue a,BasicValue b) {
            if(a.equals(b))return a;var x=domain(a);var y=domain(b);
            if(x!=null&&y!=null)return new Domain(x.zero&y.zero,x.one&y.one,Objects.equals(x.parity,y.parity)?x.parity:null,Objects.equals(x.expression,y.expression)?x.expression:null);
            return super.merge(a,b);
        }
    }
}
