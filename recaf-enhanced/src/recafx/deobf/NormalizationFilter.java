package recafx.deobf;

import jakarta.annotation.Nonnull;
import recafx.analysis.ObfuscationAnalysis;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.LocalVariable;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.mapping.gen.filter.NameGeneratorFilter;

import java.util.function.Predicate;

/**
 * Decides which names get normalized: anything illegal or confusable everywhere, plus minified
 * single-letter names inside classes that were detected as obfuscated.
 */
public class NormalizationFilter extends NameGeneratorFilter {
	private final Predicate<String> obfuscatedClass;
	private final boolean renameMinified;

	public NormalizationFilter(Predicate<String> obfuscatedClass, boolean renameMinified) {
		super(null, false);
		this.obfuscatedClass = obfuscatedClass;
		this.renameMinified = renameMinified;
	}

	@Override
	public boolean shouldMapClass(@Nonnull ClassInfo info) {
		String name = info.getName();
		if (ObfuscationAnalysis.isIllegalClassName(name)) return true;
		String simple = name.substring(name.lastIndexOf('/') + 1);
		if (ObfuscationAnalysis.isConfusable(simple)) return true;
		if (!renameMinified || !obfuscatedClass.test(name)) return false;
		// Single-letter class names, or classes living in single-letter / confusable packages (dev/clutch/I/Il/x).
		if (ObfuscationAnalysis.isSingleLetter(simple.substring(simple.lastIndexOf('$') + 1))) return true;
		for (String part : name.split("/"))
			if (part.length() <= 2 && part.matches("[A-Za-z]|[Il1O0]{2}")) return true;
		return false;
	}

	@Override
	public boolean shouldMapField(@Nonnull ClassInfo owner, @Nonnull FieldMember field) {
		String name = field.getName();
		if (ObfuscationAnalysis.isIllegalName(name) || ObfuscationAnalysis.isConfusable(name)) return true;
		return renameMinified && obfuscatedClass.test(owner.getName()) && ObfuscationAnalysis.isSingleLetter(name);
	}

	@Override
	public boolean shouldMapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
		String name = method.getName();
		if (name.startsWith("<")) return false;
		if (ObfuscationAnalysis.isIllegalName(name) || ObfuscationAnalysis.isConfusable(name)) return true;
		return renameMinified && obfuscatedClass.test(owner.getName()) && ObfuscationAnalysis.isSingleLetter(name);
	}

	@Override
	public boolean shouldMapLocalVariable(@Nonnull ClassInfo owner, @Nonnull MethodMember declaringMethod, @Nonnull LocalVariable variable) {
		String name = variable.getName();
		return name == null || ObfuscationAnalysis.isIllegalName(name) || ObfuscationAnalysis.isConfusable(name);
	}
}
