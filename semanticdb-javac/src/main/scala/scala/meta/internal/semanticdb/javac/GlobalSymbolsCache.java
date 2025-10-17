package scala.meta.internal.semanticdb.javac;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.type.NoType;
import java.util.IdentityHashMap;
import java.util.ArrayList;

import static scala.meta.internal.semanticdb.javac.Debugging.pprint;

/** Cache of SemanticDB symbols that can be referenced between files. */
public final class GlobalSymbolsCache {

	private final IdentityHashMap<Element, String> globals = new IdentityHashMap<>();
	private final SemanticdbJavacOptions options;

	public GlobalSymbolsCache(SemanticdbJavacOptions options) {
		this.options = options;
	}

	public String semanticdbSymbol(Element sym, LocalSymbolsCache locals) {
		String result = globals.get(sym);
		if (result != null)
			return result;
		String localResult = locals.get(sym);
		if (localResult != null)
			return localResult;
		result = uncachedSemanticdbSymbol(sym, locals);
		if (SemanticdbSymbols.isGlobal(result)) {
			globals.put(sym, result);
		}
		return result;
	}

	public boolean isNone(Element sym) {
		return sym == null;
	}

	private String uncachedSemanticdbSymbol(Element sym, LocalSymbolsCache locals) {
		if (isNone(sym))
			return SemanticdbSymbols.ROOT_PACKAGE;

		if (sym instanceof PackageElement) {
			if (((PackageElement) sym).isUnnamed())
				return SemanticdbSymbols.ROOT_PACKAGE;

			StringBuilder sb = new StringBuilder();
			String qualifiedName = ((PackageElement) sym).getQualifiedName().toString();
			int i = 0;
			int j = 0;
			while (j < qualifiedName.length()) {
				if (i == qualifiedName.length() || qualifiedName.charAt(i) == '.') {
					final String name = qualifiedName.substring(j, i);
					SemanticdbSymbols.Descriptor desc = new SemanticdbSymbols.Descriptor(
							SemanticdbSymbols.Descriptor.Kind.Package, name);
					sb.append(desc.encode());
					j = i + 1;
				}
				i++;
			}

			return sb.toString();
		} else
		// check for Module without referring to Module as it doesn't exist < JDK 9
		if (sym.asType() instanceof NoType)
			return SemanticdbSymbols.ROOT_PACKAGE;

		if (isAnonymousClass(sym) || isLocalVariable(sym))
			return locals.put(sym);

		String owner = semanticdbSymbol(sym.getEnclosingElement(), locals);
		if (SemanticdbSymbols.isLocal(owner))
			return locals.put(sym);

		SemanticdbSymbols.Descriptor desc = semanticdbDescriptor(sym);
		if (options.verboseEnabled && desc.kind == SemanticdbSymbols.Descriptor.Kind.None) {
			if (sym instanceof QualifiedNameable)
				pprint(((QualifiedNameable) sym).getQualifiedName().toString());
			else
				pprint(sym.getSimpleName().toString());
			pprint(String.format("sym: %s (%s - superclass %s)", sym, sym.getClass(), sym.getClass().getSuperclass()));
		}
		return SemanticdbSymbols.global(owner, desc);
	}

	private boolean isLocalVariable(Element sym) {
		switch (sym.getKind()) {
		case PARAMETER:
		case EXCEPTION_PARAMETER:
		case LOCAL_VARIABLE:
			return true;
		default:
			return false;
		}
	}

	private boolean isAnonymousClass(Element sym) {
		return sym instanceof TypeElement && sym.getSimpleName().length() == 0;
	}

	private SemanticdbSymbols.Descriptor semanticdbDescriptor(Element sym) {
		if (sym instanceof TypeElement) {
			return new SemanticdbSymbols.Descriptor(SemanticdbSymbols.Descriptor.Kind.Type,
					sym.getSimpleName().toString());
		} else if (sym instanceof ExecutableElement) {
			return new SemanticdbSymbols.Descriptor(SemanticdbSymbols.Descriptor.Kind.Method,
					sym.getSimpleName().toString(), methodDisambiguator((ExecutableElement) sym));
		} else if (sym instanceof TypeParameterElement) {
			return new SemanticdbSymbols.Descriptor(SemanticdbSymbols.Descriptor.Kind.TypeParameter,
					sym.getSimpleName().toString());
		} else if (sym instanceof VariableElement) {
			return new SemanticdbSymbols.Descriptor(SemanticdbSymbols.Descriptor.Kind.Term,
					sym.getSimpleName().toString());
		} else {
			return SemanticdbSymbols.Descriptor.NONE;
		}
	}

	/**
	 * Computes the method "disambiguator" according to the SemanticDB spec.
	 *
	 * <p>
	 * <quote> Concatenation of a left parenthesis ("("), a tag and a right
	 * parenthesis (")"). If the definition is not overloaded, the tag is empty. If
	 * the definition is overloaded, the tag is computed depending on where the
	 * definition appears in the following order:
	 *
	 * <ul>
	 * <li>non-static overloads first, following the same order as they appear in
	 * the original source,
	 * <li>static overloads secondly, following the same order as they appear in the
	 * original source
	 * </ul>
	 *
	 * </quote>
	 *
	 * <p>
	 * <a href=
	 * "https://scalameta.org/docs/semanticdb/specification.html#symbol-2">Link to
	 * SemanticDB spec</a>.
	 */
	private String methodDisambiguator(ExecutableElement sym) {
		Iterable<? extends Element> elements = sym.getEnclosingElement().getEnclosedElements();
		ArrayList<ExecutableElement> methods = new ArrayList<>();
		for (Element e : elements) {
			if (e instanceof ExecutableElement && e.getSimpleName() == sym.getSimpleName()) {
				methods.add((ExecutableElement) e);
			}
		}
		// NOTE(olafur): sort static methods last, according to the spec. Historical
		// note: this
		// requirement is
		// part of the SemanticDB spec because static methods and non-static methods
		// have a different
		// "owner" symbol.
		// There is no way to recover the definition order for a mix of static
		// nnon-static method
		// definitions.
		// In practice, it's unusual to mix static and non-static methods so this
		// shouldn't be a big
		// issue.
		methods.sort((a, b) -> Boolean.compare(a.getReceiverType() == null, b.getReceiverType() == null));
		int index = methods.indexOf(sym);
		if (index == 0)
			return "()";
		return String.format("(+%d)", index);
	}
}
