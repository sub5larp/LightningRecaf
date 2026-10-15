package recafx.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import software.coley.recaf.info.JvmClassInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Heuristic obfuscation detection: per-class scoring plus obfuscator fingerprinting.
 */
public final class ObfuscationAnalysis {
	/** Score at or above which a class is considered obfuscated. */
	public static final int THRESHOLD = 4;

	public record ClassReport(String className, int score, List<String> reasons, Set<String> fingerprints) {
		public boolean obfuscated() {return score >= THRESHOLD;}

		public String reasonSummary() {return String.join(", ", reasons);}
	}

	public record Report(Map<String, ClassReport> classes, String obfuscator, int confidence,
	                     List<String> evidence, int obfuscatedCount) {
		public boolean anyObfuscated() {return obfuscatedCount > 0;}

		public static Report empty() {
			return new Report(Map.of(), null, 0, List.of(), 0);
		}
	}

	private record Fingerprint(String name, Pattern pattern) {}

	// Case-insensitive markers that identify specific obfuscators when found in names / strings.
	private static final List<Fingerprint> FINGERPRINTS = List.of(
			fp("ZKM (Zelix KlassMaster)", "zelix|klassmaster|(^|[^a-z0-9])zkm([^a-z]|$)"),
			fp("Allatori", "allatori"),
			fp("Stringer", "(^|[^a-z])stringer([^a-z]|$)|com/licel|licel\\."),
			fp("Skidfuscator", "skidfuscator|(^|[^a-z])skid(_|fusc|\\$)"),
			fp("Bozar", "(^|[^a-z])bozar([^a-z]|$)"),
			fp("Branchlock", "branchlock"),
			fp("Paramorphism", "paramorphism"),
			fp("DashO", "(^|[^a-z])dasho|com/preemptive"),
			fp("Caesium", "(^|[^a-z])caesium([^a-z]|$)"),
			fp("Radon", "(^|[^a-z])radon(_?obf|([^a-z]|$))"),
			fp("Grunt", "(^|[^a-z])grunt(obf|fuscator)"),
			fp("qProtect", "qprotect"),
			fp("BinScure", "binscure"),
			fp("JObf", "(^|[^a-z])jobf([^a-z]|$)|jobfuscator"),
			fp("SuperBlaubeere27 obfuscator", "superblaubeere27"),
			fp("NeonObf", "neonobf"),
			fp("Smoke", "smoke_?obf|smokeobfuscator"),
			fp("ClassGuard", "classguard"),
			fp("yGuard", "yguard"),
			fp("JNIC / native wrapper", "(^|[^a-z])jnic([^a-z]|$)"),
			fp("Obzcure", "obzcure"),
			fp("Lab-Obf", "lab_?obf"),
			fp("Cafebabe", "cafebabe_?obf"),
			fp("Sandmark", "sandmark"),
			fp("Mazeobf", "mazeobf|maze_obf"),
			fp("Zelix ZKM (string tables)", "ZKMObfuscator")
	);

	private static Fingerprint fp(String name, String regex) {
		return new Fingerprint(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
	}

	private static final Pattern CONFUSABLE = Pattern.compile("^[Il1O0]{3,}$|^(.)\\1{3,}$|^[\\u0400-\\u04FF]+$|^[\\u0080-\\u00FF]+$");
	private static final Pattern SINGLE_LETTER = Pattern.compile("^[a-zA-Z]$|^[a-zA-Z]\\$?[a-zA-Z]?$");
	private static final Pattern IDENT = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
	private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
			"interface", "long", "native", "new", "package", "private", "protected", "public", "return", "short",
			"static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try",
			"void", "volatile", "while", "true", "false", "null");

	private ObfuscationAnalysis() {}

	public static boolean isIllegalName(String name) {
		if (name == null || name.isEmpty()) return true;
		if (KEYWORDS.contains(name)) return true;
		if (!Character.isJavaIdentifierStart(name.codePointAt(0))) return true;
		for (int i = Character.charCount(name.codePointAt(0)); i < name.length();) {
			int cp = name.codePointAt(i);
			if (!Character.isJavaIdentifierPart(cp) || Character.isIdentifierIgnorable(cp)) return true;
			i += Character.charCount(cp);
		}
		return false;
	}

	public static boolean isIllegalClassName(String internalName) {
		if (internalName == null || internalName.isEmpty()) return true;
		for (String part : internalName.split("/", -1))
			if (isIllegalName(part)) return true;
		return false;
	}

	public static boolean isConfusable(String name) {
		return name != null && (name.length() > 64 || CONFUSABLE.matcher(name).matches());
	}

	public static boolean isSingleLetter(String name) {
		return name != null && SINGLE_LETTER.matcher(name).matches();
	}

	public static ClassReport analyzeClass(JvmClassInfo info) {
		List<String> reasons = new ArrayList<>();
		Set<String> fingerprints = new LinkedHashSet<>();
		int score = 0;
		String className = info.getName();
		ClassNode node = new ClassNode();
		try {
			info.getClassReader().accept(node, ClassReader.SKIP_FRAMES);
		} catch (Throwable t) {
			reasons.add("class could not be parsed (" + t.getClass().getSimpleName() + ")");
			return new ClassReport(className, THRESHOLD, reasons, fingerprints);
		}

		// --- Names -------------------------------------------------------------------------------
		int illegal = 0, confusable = 0, single = 0, members = 0;
		if (isIllegalClassName(className)) illegal++;
		else if (isConfusable(simpleName(className))) confusable++;
		for (FieldNode f : node.fields) {
			members++;
			if (isIllegalName(f.name)) illegal++;
			else if (isConfusable(f.name)) confusable++;
			else if (isSingleLetter(f.name)) single++;
		}
		for (MethodNode m : node.methods) {
			if (m.name.startsWith("<")) continue;
			if ((m.access & Opcodes.ACC_SYNTHETIC) != 0 && m.name.startsWith("lambda$")) continue;
			members++;
			if (isIllegalName(m.name)) illegal++;
			else if (isConfusable(m.name)) confusable++;
			else if (isSingleLetter(m.name)) single++;
		}
		if (illegal > 0) {
			score += Math.min(6, 3 + illegal / 4);
			reasons.add("illegal/unreadable names (" + illegal + ")");
		}
		if (confusable > 0) {
			score += Math.min(4, 2 + confusable / 4);
			reasons.add("confusable names (" + confusable + ")");
		}
		if (members >= 3 && single * 100 / Math.max(1, members) >= 70) {
			score += 1;
			reasons.add("minified member names (" + single + "/" + members + ")");
		}

		// --- Strings -----------------------------------------------------------------------------
		int garbageStrings = 0, longStrings = 0;
		Set<String> strings = new LinkedHashSet<>();
		try {
			strings.addAll(info.getStringConstants());
		} catch (Throwable ignored) {}
		for (String s : strings) {
			if (s.length() >= 4 && unprintableRatio(s) >= 0.3) garbageStrings++;
			if (s.length() >= 300 && unprintableRatio(s) >= 0.1) longStrings++;
		}
		if (garbageStrings >= 3) {
			score += Math.min(4, 2 + garbageStrings / 8);
			reasons.add("encrypted-looking strings (" + garbageStrings + ")");
		} else if (longStrings > 0) {
			score += 1;
			reasons.add("large binary string blobs (" + longStrings + ")");
		}

		// --- Structure ---------------------------------------------------------------------------
		int decryptCalls = 0, customIndy = 0, flowMethods = 0, flattened = 0, exceptionFlow = 0, junkSwitch = 0;
		Set<String> ownStaticStringMethods = new LinkedHashSet<>();
		Set<String> calledStaticMethods = new LinkedHashSet<>();
		for (MethodNode m : node.methods)
			if ((m.access & Opcodes.ACC_STATIC) != 0 && m.desc.endsWith(")Ljava/lang/String;") && onlySimpleArgs(m.desc))
				ownStaticStringMethods.add(m.name + m.desc);
		boolean stackTraceKeyed = false;
		for (MethodNode m : node.methods) {
			InsnList insns = m.instructions;
			if (insns == null || insns.size() == 0) continue;
			int total = 0, jumps = 0, switches = 0, athrows = 0, nulls = 0;
			boolean usesStackTrace = false;
			for (AbstractInsnNode insn : insns) {
				int op = insn.getOpcode();
				if (op < 0) continue;
				total++;
				if (insn instanceof JumpInsnNode) jumps++;
				else if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) switches++;
				else if (op == Opcodes.ATHROW) athrows++;
				else if (op == Opcodes.ACONST_NULL) nulls++;
				else if (insn instanceof MethodInsnNode min) {
					if (min.getOpcode() == Opcodes.INVOKESTATIC && min.owner.equals(className)
							&& !(min.name.equals(m.name) && min.desc.equals(m.desc)))
						calledStaticMethods.add(min.name + min.desc);
					if (min.getOpcode() == Opcodes.INVOKESTATIC && min.owner.equals(className)
							&& ownStaticStringMethods.contains(min.name + min.desc) && hasConstantArgs(min))
						decryptCalls++;
					if (min.owner.equals("java/lang/Thread") && min.name.equals("getStackTrace")
							|| min.owner.equals("java/lang/Throwable") && min.name.equals("getStackTrace")
							|| min.owner.equals("java/lang/StackTraceElement"))
						usesStackTrace = true;
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					Handle bsm = indy.bsm;
					String owner = bsm.getOwner();
					if (!owner.startsWith("java/lang/invoke/") && !owner.startsWith("java/lang/runtime/"))
						customIndy++;
				}
			}
			if (usesStackTrace && ownStaticStringMethods.contains(m.name + m.desc)) stackTraceKeyed = true;
			if (total >= 24 && jumps * 100 / total >= 35) flowMethods++;
			if (total >= 40 && switches >= 1 && jumps >= 6 && hasBackwardGotoToSwitch(insns)) flattened++;
			if (total >= 12 && athrows >= 2 && nulls >= 2 && athrows * 100 / total >= 8) exceptionFlow++;
			if (switches >= 3 && total < 60) junkSwitch++;
		}
		if (decryptCalls >= 2) {
			score += Math.min(5, 3 + decryptCalls / 10);
			reasons.add("string decryption calls (" + decryptCalls + ")");
		}
		if (stackTraceKeyed) {
			score += 2;
			reasons.add("stack-trace keyed string decryption (Allatori/Stringer style)");
			fingerprints.add("Allatori/Stringer-style string encryption");
		}
		if (customIndy > 0) {
			score += Math.min(5, 3 + customIndy / 10);
			reasons.add("custom invokedynamic bootstraps (" + customIndy + ")");
		}
		if (flowMethods > 0) {
			score += Math.min(4, 2 + flowMethods / 5);
			reasons.add("control-flow obfuscation (" + flowMethods + " methods)");
		}
		if (flattened > 0) {
			score += 3;
			reasons.add("flattened control flow (" + flattened + " methods)");
		}
		if (exceptionFlow > 0) {
			score += 2;
			reasons.add("exception-based flow (" + exceptionFlow + " methods)");
		}
		if (junkSwitch > 0) {
			score += 1;
			reasons.add("junk switch tables (" + junkSwitch + ")");
		}
		// These encodings need no illegal names, custom attributes or large string blobs.
		// Recognize the decoder and its use together so names alone cannot trigger recovery.
		if (hasSkidByteStrings(node, calledStaticMethods)) {
			score += THRESHOLD;
			reasons.add("seeded XOR byte-array string decoder");
			fingerprints.add("Skidfuscator-style string encryption");
		}
		if (hasXorStringTable(node)) {
			score += THRESHOLD;
			reasons.add("XOR-decoded static string table");
			fingerprints.add("ZKM-style XOR string tables");
		}

		// --- Attributes / metadata ---------------------------------------------------------------
		if (node.signature != null && !looksLikeSignature(node.signature)) {
			score += 3;
			reasons.add("bogus generic signature");
		}
		int bogusMemberSigs = 0;
		for (FieldNode f : node.fields) if (f.signature != null && !looksLikeSignature(f.signature)) bogusMemberSigs++;
		for (MethodNode m : node.methods) if (m.signature != null && !looksLikeSignature(m.signature)) bogusMemberSigs++;
		if (bogusMemberSigs > 0) {
			score += 2;
			reasons.add("bogus member signatures (" + bogusMemberSigs + ")");
		}
		if (node.sourceFile != null && (node.sourceFile.length() > 80 || unprintableRatio(node.sourceFile) > 0.2)) {
			score += 1;
			reasons.add("junk SourceFile attribute");
		}
		int annotationCount = size(node.visibleAnnotations) + size(node.invisibleAnnotations);
		if (annotationCount > 20) {
			score += 2;
			reasons.add("annotation spam (" + annotationCount + ")");
		}
		int nativeMethods = 0;
		for (MethodNode m : node.methods) if ((m.access & Opcodes.ACC_NATIVE) != 0 && !m.name.startsWith("<")) nativeMethods++;
		if (nativeMethods >= 3 && nativeMethods * 100 / Math.max(1, node.methods.size()) >= 50) {
			score += 2;
			reasons.add("most methods native (JNI obfuscation?)");
		}

		// --- Fingerprints ------------------------------------------------------------------------
		StringBuilder haystack = new StringBuilder(className).append('\n');
		if (node.superName != null) haystack.append(node.superName).append('\n');
		if (node.sourceFile != null) haystack.append(node.sourceFile).append('\n');
		for (FieldNode f : node.fields) haystack.append(f.name).append('\n');
		for (MethodNode m : node.methods) {
			haystack.append(m.name).append('\n');
			if (m.instructions != null)
				for (AbstractInsnNode insn : m.instructions)
					if (insn instanceof InvokeDynamicInsnNode indy)
						haystack.append(indy.bsm.getOwner()).append('.').append(indy.bsm.getName()).append('\n');
		}
		// Custom (unknown) attributes: ZKM stamps every class with an attribute named like "ZKM22.0.3E".
		java.util.List<String> attrNames = new java.util.ArrayList<>();
		if (node.attrs != null) for (org.objectweb.asm.Attribute a : node.attrs) attrNames.add(a.type);
		for (MethodNode m : node.methods) if (m.attrs != null) for (org.objectweb.asm.Attribute a : m.attrs) attrNames.add(a.type);
		for (FieldNode f : node.fields) if (f.attrs != null) for (org.objectweb.asm.Attribute a : f.attrs) attrNames.add(a.type);
		for (String attr : attrNames) {
			haystack.append("attr:").append(attr).append('\n');
			java.util.regex.Matcher zkm = java.util.regex.Pattern.compile("^ZKM(\\d+(?:\\.\\d+)*)").matcher(attr);
			if (zkm.find()) fingerprints.add("ZKM (Zelix KlassMaster) " + zkm.group(1));
		}
		for (AnnotationNode a : nullSafe(node.visibleAnnotations)) haystack.append(a.desc).append('\n');
		for (AnnotationNode a : nullSafe(node.invisibleAnnotations)) haystack.append(a.desc).append('\n');
		for (String s : strings) if (s.length() < 200) haystack.append(s).append('\n');
		String hay = haystack.toString();
		for (Fingerprint fingerprint : FINGERPRINTS)
			if (fingerprint.pattern.matcher(hay).find())
				fingerprints.add(fingerprint.name);
		if (!fingerprints.isEmpty()) {
			score += 3;
			reasons.add("obfuscator fingerprint: " + String.join(", ", fingerprints));
		}

		return new ClassReport(className, score, Collections.unmodifiableList(reasons), Collections.unmodifiableSet(fingerprints));
	}

	public static Report summarize(Collection<ClassReport> reports) {
		Map<String, ClassReport> classes = new TreeMap<>();
		Map<String, Integer> fingerprintCounts = new HashMap<>();
		int obfuscated = 0;
		Map<String, Integer> reasonCounts = new LinkedHashMap<>();
		for (ClassReport report : reports) {
			classes.put(report.className(), report);
			if (report.obfuscated()) obfuscated++;
			for (String f : report.fingerprints()) fingerprintCounts.merge(f, 1, Integer::sum);
			for (String r : report.reasons()) {
				int paren = r.indexOf(" (");
				reasonCounts.merge(paren > 0 ? r.substring(0, paren) : r, 1, Integer::sum);
			}
		}
		String obfuscator = null;
		int confidence = 0;
		List<String> evidence = new ArrayList<>();
		// A fingerprint alone is not enough (library code may mention an obfuscator by name); require that
		// something actually looks obfuscated before naming a product.
		if (!fingerprintCounts.isEmpty() && obfuscated > 0) {
			Map.Entry<String, Integer> best = null;
			for (Map.Entry<String, Integer> e : fingerprintCounts.entrySet())
				if (best == null || e.getValue() > best.getValue()) best = e;
			obfuscator = best.getKey();
			confidence = Math.min(95, 70 + best.getValue() * 5);
			fingerprintCounts.forEach((k, v) -> evidence.add(k + " fingerprint in " + v + " class" + (v == 1 ? "" : "es")));
		} else if (obfuscated > 0) {
			obfuscator = "Unknown obfuscator";
			confidence = Math.min(85, 40 + obfuscated * 2);
		}
		reasonCounts.entrySet().stream()
				.sorted((a, b) -> b.getValue() - a.getValue())
				.limit(8)
				.forEach(e -> evidence.add(e.getKey() + " in " + e.getValue() + " class" + (e.getValue() == 1 ? "" : "es")));
		return new Report(Collections.unmodifiableMap(classes), obfuscator, confidence, evidence, obfuscated);
	}

	// ---------------------------------------------------------------------------------------------

	private static boolean onlySimpleArgs(String desc) {
		for (Type t : Type.getArgumentTypes(desc)) {
			int sort = t.getSort();
			if (sort == Type.OBJECT && !t.getInternalName().equals("java/lang/String") && !t.getInternalName().equals("java/lang/Object"))
				return false;
			if (sort == Type.ARRAY) return false;
		}
		return Type.getArgumentTypes(desc).length > 0;
	}

	private static boolean hasSkidByteStrings(ClassNode node, Set<String> calledMethods) {
		for (MethodNode decoder : node.methods) {
			if ((decoder.access & Opcodes.ACC_STATIC) == 0
					|| !calledMethods.contains(decoder.name + decoder.desc)
					|| !(decoder.desc.equals("([BI)Ljava/lang/String;") || decoder.desc.equals("([B[BI)Ljava/lang/String;"))) continue;
			if (!hasOpcodes(decoder, Opcodes.IXOR, Opcodes.IREM, Opcodes.BALOAD, Opcodes.BASTORE)
					|| !invokes(decoder, "java/lang/Integer", "toString", "(I)Ljava/lang/String;")
					|| !invokes(decoder, "java/lang/String", "getBytes", "()[B")
					|| !(invokes(decoder, "java/lang/String", "<init>", "([BLjava/nio/charset/Charset;)V")
					|| invokes(decoder, "java/lang/String", "<init>", "([BLjava/lang/String;)V"))) continue;
			return true;
		}
		return false;
	}

	private static boolean hasXorStringTable(ClassNode node) {
		MethodNode initializer = node.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
		if (initializer == null || !hasOpcodes(initializer, Opcodes.AASTORE)) return false;
		Set<String> tables = new LinkedHashSet<>();
		for (FieldNode field : node.fields)
			if ((field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
					== (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
					&& field.desc.equals("[Ljava/lang/String;")) tables.add(field.name);
		Set<String> initialized = new LinkedHashSet<>();
		Set<String> initializerCalls = new LinkedHashSet<>();
		for (AbstractInsnNode insn : initializer.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC && field.owner.equals(node.name))
				initialized.add(field.name);
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(node.name))
				initializerCalls.add(call.name + call.desc);
		}
		tables.retainAll(initialized);
		if (tables.isEmpty()) return false;
		boolean consumer = false;
		for (MethodNode method : node.methods) {
			if (method == initializer || !hasOpcodes(method, Opcodes.AALOAD)) continue;
			for (AbstractInsnNode insn : method.instructions)
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
						&& field.owner.equals(node.name) && tables.contains(field.name)) { consumer = true; break; }
			if (consumer) break;
		}
		if (!consumer) return false;
		for (MethodNode decoder : node.methods) {
			if ((decoder.access & Opcodes.ACC_STATIC) == 0 || !decoder.desc.equals("([C)Ljava/lang/String;")
					|| !initializerCalls.contains(decoder.name + decoder.desc)) continue;
			if (hasOpcodes(decoder, Opcodes.IXOR, Opcodes.IREM, Opcodes.CALOAD, Opcodes.CASTORE)
					&& (hasOpcodes(decoder, Opcodes.TABLESWITCH) || hasOpcodes(decoder, Opcodes.LOOKUPSWITCH))
					&& invokes(decoder, "java/lang/String", "<init>", "([C)V")
					&& invokes(decoder, "java/lang/String", "intern", "()Ljava/lang/String;")) return true;
		}
		return false;
	}

	private static boolean invokes(MethodNode method, String owner, String name, String desc) {
		for (AbstractInsnNode insn : method.instructions)
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) return true;
		return false;
	}

	private static boolean hasOpcodes(MethodNode method, int... opcodes) {
		for (int opcode : opcodes) {
			boolean found = false;
			for (AbstractInsnNode insn : method.instructions)
				if (insn.getOpcode() == opcode) { found = true; break; }
			if (!found) return false;
		}
		return true;
	}

	private static boolean hasConstantArgs(MethodInsnNode min) {
		int argCount = Type.getArgumentTypes(min.desc).length;
		AbstractInsnNode prev = min.getPrevious();
		int seen = 0;
		while (prev != null && seen < argCount) {
			int op = prev.getOpcode();
			if (op < 0) {
				prev = prev.getPrevious();
				continue;
			}
			boolean constant = prev instanceof LdcInsnNode
					|| (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
					|| op == Opcodes.BIPUSH || op == Opcodes.SIPUSH
					|| op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1;
			if (!constant) return false;
			seen++;
			prev = prev.getPrevious();
		}
		return seen == argCount;
	}

	private static boolean hasBackwardGotoToSwitch(InsnList insns) {
		for (AbstractInsnNode insn : insns) {
			if (insn.getOpcode() == Opcodes.GOTO && insn instanceof JumpInsnNode jump) {
				int target = insns.indexOf(jump.label);
				int self = insns.indexOf(insn);
				if (target < self) {
					// Walk forward from the label to see if it leads to a switch quickly.
					AbstractInsnNode cur = jump.label;
					for (int i = 0; i < 8 && cur != null; i++, cur = cur.getNext())
						if (cur instanceof TableSwitchInsnNode || cur instanceof LookupSwitchInsnNode) return true;
				}
			}
		}
		return false;
	}

	private static boolean looksLikeSignature(String signature) {
		if (signature.length() > 600) return false;
		int depth = 0;
		for (int i = 0; i < signature.length(); i++) {
			char c = signature.charAt(i);
			if (c == '<') depth++;
			else if (c == '>') depth--;
			if (depth < 0) return false;
			if (c < 0x20 || c > 0x7e) return false;
		}
		return depth == 0 && (signature.indexOf(';') >= 0 || signature.indexOf('T') >= 0 || signature.indexOf('(') >= 0);
	}

	private static double unprintableRatio(String s) {
		int bad = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') bad++;
			else if (c >= 0x7f && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) bad++;
			else if (c > 0x2fff && !Character.isLetterOrDigit(c)) bad++;
		}
		return (double) bad / s.length();
	}

	private static String simpleName(String internalName) {
		int i = internalName.lastIndexOf('/');
		String simple = i < 0 ? internalName : internalName.substring(i + 1);
		int inner = simple.lastIndexOf('$');
		return inner >= 0 && inner < simple.length() - 1 ? simple.substring(inner + 1) : simple;
	}

	private static int size(List<?> list) {return list == null ? 0 : list.size();}

	private static <T> List<T> nullSafe(List<T> list) {return list == null ? List.of() : list;}

	public static String lower(String s) {return s.toLowerCase(Locale.ROOT);}
}
