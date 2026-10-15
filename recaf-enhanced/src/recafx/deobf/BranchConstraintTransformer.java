package recafx.deobf;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.util.*;

/** Original path-sensitive integer branch cleanup. Facts describe current local
 * values, never fields or calls. Both edges of an unknown flag remain executable;
 * stores invalidate facts and joins retain only facts true on every incoming edge. */
public final class BranchConstraintTransformer extends LocalRewriteTransformer implements Opcodes {
    private static final int MAX_STEPS = 100_000, MAX_FACTS = 32, MAX_EXCLUDED = 8;
    @Override public String identifier() { return "lightning.flow.branch-constraints"; }

    @Override public int rewrite(ClassNode owner) {
        int changed = 0;
        for (MethodNode method : owner.methods) {
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0 || method.instructions.size() > 12_000
                    || method.maxLocals > 512 || method.tryCatchBlocks.size() > 128) continue;
            AbstractInsnNode[] code = method.instructions.toArray();
            if (Arrays.stream(code).filter(i -> test(i) != null).limit(2).count() < 2) continue;
            State[] states;
            try {
                new Analyzer<>(new BasicVerifier()).analyze(owner.name, method);
                states = analyze(method, code);
            } catch (AnalyzerException | IllegalArgumentException invalid) { continue; }
            for (int i = 0; i < code.length; i++) {
                Test test = test(code[i]);
                if (test == null || states[i] == null) continue;
                Domain value = states[i].get(test.local);
                boolean yes = value.restrict(test.relation, test.constant) != null;
                boolean no = value.restrict(test.relation ^ 1, test.constant) != null;
                if (yes == no) continue;
                JumpInsnNode jump = (JumpInsnNode) code[i];
                // Keep all operand evaluation. Only the decision itself is redundant.
                method.instructions.insertBefore(jump, new InsnNode(test.operands == 1 ? POP : POP2));
                if (yes) method.instructions.set(jump, new JumpInsnNode(GOTO, jump.label));
                else method.instructions.remove(jump);
                changed++;
            }
        }
        return changed;
    }

    private static State[] analyze(MethodNode method, AbstractInsnNode[] code) throws AnalyzerException {
        State[] states = new State[code.length];
        if (code.length == 0) return states;
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        for (int i = 0; i < code.length; i++) positions.put(code[i], i);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        boolean[] queued = new boolean[code.length];
        states[0] = new State(); queue.add(0); queued[0] = true;
        int steps = 0;
        while (!queue.isEmpty()) {
            if (++steps > MAX_STEPS || Thread.currentThread().isInterrupted())
                throw new AnalyzerException(null, "Branch constraint analysis budget");
            int at = queue.removeFirst(); queued[at] = false;
            AbstractInsnNode insn = code[at]; int op = insn.getOpcode();
            steps += method.tryCatchBlocks.size();
            if (insn instanceof LookupSwitchInsnNode sw) steps += sw.labels.size();
            if (insn instanceof TableSwitchInsnNode sw) steps += sw.labels.size();
            if (steps > MAX_STEPS) throw new AnalyzerException(insn, "Branch edge budget");
            if (op == JSR || op == RET) throw new AnalyzerException(insn, "Legacy subroutine retained");
            State before = states[at], after = before;
            if (insn instanceof VarInsnNode local && op >= ISTORE && op <= ASTORE) {
                after = before.without(local.var);
                if (op == LSTORE || op == DSTORE) after = after.without(local.var + 1);
            } else if (insn instanceof IincInsnNode inc) after = before.without(inc.var);
            if (insn instanceof JumpInsnNode jump) {
                Test test = test(insn);
                merge(positions.get(jump.label), test == null ? after : after.restrict(test, true), states, queue, queued);
                if (op != GOTO) merge(at + 1, test == null ? after : after.restrict(test, false), states, queue, queued);
            } else if (insn instanceof LookupSwitchInsnNode sw) {
                Integer local = loadedLocal(previous(insn));
                State fallback = after;
                for (int i = 0; i < sw.keys.size(); i++) {
                    int key = sw.keys.get(i);
                    merge(positions.get(sw.labels.get(i)), local == null ? after : after.restrict(new Test(local, 0, key, 1), true), states, queue, queued);
                    if (local != null && fallback != null) fallback = fallback.restrict(new Test(local, 1, key, 1), true);
                }
                merge(positions.get(sw.dflt), fallback, states, queue, queued);
            } else if (insn instanceof TableSwitchInsnNode sw) {
                Integer local = loadedLocal(previous(insn));
                State fallback = after;
                for (int i = 0; i < sw.labels.size(); i++) {
                    int key = sw.min + i;
                    merge(positions.get(sw.labels.get(i)), local == null ? after : after.restrict(new Test(local, 0, key, 1), true), states, queue, queued);
                    if (local != null && fallback != null) fallback = fallback.restrict(new Test(local, 1, key, 1), true);
                }
                merge(positions.get(sw.dflt), fallback, states, queue, queued);
            } else if (!(op >= IRETURN && op <= RETURN) && op != ATHROW) {
                merge(at + 1, after, states, queue, queued);
            }
            // Conservative exceptional edges use the state before each protected
            // instruction, including nonthrowing instructions. No catch is discarded.
            for (TryCatchBlockNode handler : method.tryCatchBlocks) {
                if (at >= positions.get(handler.start) && at < positions.get(handler.end))
                    merge(positions.get(handler.handler), before, states, queue, queued);
            }
        }
        return states;
    }

    private static void merge(int at, State incoming, State[] states, Deque<Integer> queue, boolean[] queued) {
        if (incoming == null || at >= states.length) return;
        State merged = states[at] == null ? incoming : states[at].join(incoming);
        if (!merged.equals(states[at])) {
            states[at] = merged;
            if (!queued[at]) { queue.add(at); queued[at] = true; }
        }
    }

    // A label can be an alternate entry between a load and its consumer. Never
    // look through labels when identifying operands, even if their frames agree.
    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        if (insn == null) return null;
        AbstractInsnNode p = insn.getPrevious();
        while (p instanceof LineNumberNode || p instanceof FrameNode) p = p.getPrevious();
        return p instanceof LabelNode ? null : p;
    }
    private static Integer loadedLocal(AbstractInsnNode insn) {
        return insn instanceof VarInsnNode v && v.getOpcode() == ILOAD ? v.var : null;
    }
    private static Integer integer(AbstractInsnNode insn) {
        if (insn == null) return null;
        int op = insn.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (insn instanceof IntInsnNode n && (op == BIPUSH || op == SIPUSH)) return n.operand;
        return insn instanceof LdcInsnNode l && l.cst instanceof Integer n ? n : null;
    }
    private static Test test(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (!(insn instanceof JumpInsnNode)) return null;
        AbstractInsnNode right = previous(insn);
        if (op >= IFEQ && op <= IFLE) {
            Integer local = loadedLocal(right);
            return local == null ? null : new Test(local, op - IFEQ, 0, 1);
        }
        if (op < IF_ICMPEQ || op > IF_ICMPLE) return null;
        AbstractInsnNode left = previous(right);
        Integer local = loadedLocal(left), constant = integer(right);
        if (local != null && constant != null) return new Test(local, op - IF_ICMPEQ, constant, 2);
        local = loadedLocal(right); constant = integer(left);
        if (local == null || constant == null) return null;
        int relation = switch (op) {
            case IF_ICMPLT -> 4; case IF_ICMPGE -> 5;
            case IF_ICMPGT -> 2; case IF_ICMPLE -> 3;
            default -> op - IF_ICMPEQ;
        };
        return new Test(local, relation, constant, 2);
    }
    private record Test(int local, int relation, int constant, int operands) {}
    private record Domain(int min, int max, Set<Integer> excluded) {
        static final Domain ANY = new Domain(Integer.MIN_VALUE, Integer.MAX_VALUE, Set.of());
        Domain restrict(int relation, int value) {
            long low = min, high = max;
            Set<Integer> holes = excluded;
            switch (relation) {
                case 0 -> { low = Math.max(low, value); high = Math.min(high, value); }
                case 1 -> {
                    if (value == low) low++;
                    else if (value == high) high--;
                    else if (value > low && value < high && holes.size() < MAX_EXCLUDED) {
                        var copy = new HashSet<>(holes); copy.add(value); holes = Set.copyOf(copy);
                    }
                }
                case 2 -> high = Math.min(high, (long) value - 1);
                case 3 -> low = Math.max(low, value);
                case 4 -> low = Math.max(low, (long) value + 1);
                case 5 -> high = Math.min(high, value);
                default -> throw new IllegalArgumentException("Unknown integer relation");
            }
            while (low <= high && holes.contains((int) low)) low++;
            while (low <= high && holes.contains((int) high)) high--;
            if (low > high) return null;
            int a = (int) low, b = (int) high;
            if (!holes.isEmpty()) {
                var copy = new HashSet<Integer>();
                for (int hole : holes) if (hole > a && hole < b) copy.add(hole);
                holes = Set.copyOf(copy);
            }
            return new Domain(a, b, holes);
        }
        Domain join(Domain other) {
            var holes = new HashSet<>(excluded); holes.retainAll(other.excluded);
            return new Domain(Math.min(min, other.min), Math.max(max, other.max), Set.copyOf(holes));
        }
    }
    private static final class State {
        final Map<Integer, Domain> locals;
        State() { locals = Map.of(); }
        State(Map<Integer, Domain> locals) { this.locals = Map.copyOf(locals); }
        Domain get(int local) { return locals.getOrDefault(local, Domain.ANY); }
        State without(int local) {
            if (!locals.containsKey(local)) return this;
            var copy = new HashMap<>(locals); copy.remove(local); return new State(copy);
        }
        State restrict(Test test, boolean yes) {
            Domain value = get(test.local).restrict(yes ? test.relation : test.relation ^ 1, test.constant);
            if (value == null) return null;
            if (value.equals(get(test.local))) return this;
            if (!locals.containsKey(test.local) && locals.size() >= MAX_FACTS) return this;
            var copy = new HashMap<>(locals); copy.put(test.local, value); return new State(copy);
        }
        State join(State other) {
            if (locals.isEmpty()) return this;
            var copy = new HashMap<Integer, Domain>();
            for (var entry : locals.entrySet()) {
                Domain otherValue = other.locals.get(entry.getKey());
                if (otherValue != null) {
                    Domain joined = entry.getValue().join(otherValue);
                    if (!joined.equals(Domain.ANY)) copy.put(entry.getKey(), joined);
                }
            }
            return new State(copy);
        }
        @Override public boolean equals(Object other) { return other instanceof State s && locals.equals(s.locals); }
        @Override public int hashCode() { return locals.hashCode(); }
    }
}
