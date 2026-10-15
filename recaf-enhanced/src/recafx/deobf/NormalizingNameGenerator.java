package recafx.deobf;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.LocalVariable;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.mapping.gen.naming.DeconflictingNameGenerator;
import software.coley.recaf.workspace.model.Workspace;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates readable, deterministic replacement names that keep the original package layout:
 * {@code Class1}, {@code field1}, {@code method1} (numbered per owner for members).
 */
public class NormalizingNameGenerator implements DeconflictingNameGenerator {
	private final Map<String, Integer> classCounters = new HashMap<>();
	private final Map<String, Integer> fieldCounters = new HashMap<>();
	private final Map<String, Integer> methodCounters = new HashMap<>();
	private Workspace workspace;

	@Override
	public void setWorkspace(@Nullable Workspace workspace) {
		this.workspace = workspace;
	}

	@Nonnull
	@Override
	public String mapClass(@Nonnull ClassInfo info) {
		String name = info.getName();
		String pkg = name.lastIndexOf('/') < 0 ? "" : name.substring(0, name.lastIndexOf('/') + 1);
		// Keep legal package segments, replace illegal ones.
		StringBuilder cleanPkg = new StringBuilder();
		if (!pkg.isEmpty()) {
			for (String part : pkg.substring(0, pkg.length() - 1).split("/", -1)) {
				if (recafx.analysis.ObfuscationAnalysis.isIllegalName(part) || recafx.analysis.ObfuscationAnalysis.isConfusable(part))
					part = "pkg" + Math.abs(part.hashCode() % 1000);
				else if (part.length() <= 2 && part.matches("[A-Za-z]|[Il1O0]{2}"))
					part = "pkg_" + part; // keeps the original token visible but makes I / l / Il distinguishable
				cleanPkg.append(part).append('/');
			}
		}
		String prefix = cleanPkg.toString();
		String kind = info.hasInterfaceModifier() ? "Interface" : info.hasEnumModifier() ? "Enum" : info.hasAnnotationModifier() ? "Annotation" : "Class";
		// Readable simple names (only the package was the problem) are kept as they are.
		String simple = name.substring(name.lastIndexOf('/') + 1);
		if (!recafx.analysis.ObfuscationAnalysis.isIllegalName(simple) && !recafx.analysis.ObfuscationAnalysis.isConfusable(simple)
				&& !recafx.analysis.ObfuscationAnalysis.isSingleLetter(simple.substring(simple.lastIndexOf('$') + 1))
				&& (workspace == null || workspace.findClass(prefix + simple) == null))
			return prefix + simple;
		String candidate;
		do {
			int n = classCounters.merge(prefix + kind, 1, Integer::sum);
			candidate = prefix + kind + n;
		} while (workspace != null && workspace.findClass(candidate) != null);
		return candidate;
	}

	@Nonnull
	@Override
	public String mapField(@Nonnull ClassInfo owner, @Nonnull FieldMember field) {
		String key = owner.getName();
		String candidate;
		do {
			int n = fieldCounters.merge(key, 1, Integer::sum);
			candidate = (field.hasStaticModifier() ? "staticField" : "field") + n;
		} while (owner.getDeclaredField(candidate, field.getDescriptor()) != null);
		return candidate;
	}

	@Nonnull
	@Override
	public String mapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
		String key = owner.getName();
		String candidate;
		do {
			int n = methodCounters.merge(key, 1, Integer::sum);
			candidate = (method.hasStaticModifier() ? "staticMethod" : "method") + n;
		} while (owner.getDeclaredMethod(candidate, method.getDescriptor()) != null);
		return candidate;
	}

	@Nonnull
	@Override
	public String mapVariable(@Nonnull ClassInfo owner, @Nonnull MethodMember declaringMethod, @Nonnull LocalVariable variable) {
		return "var" + variable.getIndex();
	}
}
