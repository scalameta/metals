package scala.meta.internal.semanticdb.javac;

import scala.meta.internal.jsemanticdb.Semanticdb.*;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.List;
import java.util.stream.Collectors;

import scala.meta.internal.jsemanticdb.Semanticdb;
import static scala.meta.internal.jsemanticdb.SemanticdbBuilders.*;
import static scala.meta.internal.semanticdb.javac.SemanticdbTypeVisitor.UNRESOLVED_TYPE_REF;

public final class SemanticdbSignatures {
	private final GlobalSymbolsCache cache;
	private final LocalSymbolsCache locals;
	private final Types types;

	public SemanticdbSignatures(GlobalSymbolsCache cache, LocalSymbolsCache locals, Types types) {
		this.cache = cache;
		this.locals = locals;
		this.types = types;
	}

	public Signature generateSignature(Element sym) {
		if (sym instanceof TypeElement) {
			return generateClassSignature((TypeElement) sym);
		} else if (sym instanceof ExecutableElement) {
			return generateMethodSignature((ExecutableElement) sym);
		} else if (sym instanceof VariableElement) {
			return generateFieldSignature((VariableElement) sym);
		} else if (sym instanceof TypeParameterElement) {
			return generateTypeSignature((TypeParameterElement) sym);
		}
		return null;
	}

	private Signature generateClassSignature(TypeElement sym) {
		ClassSignature.Builder builder = ClassSignature.newBuilder();

		builder.setTypeParameters(generateScope(sym.getTypeParameters()));

		for (TypeMirror superType : types.directSupertypes(sym.asType())) {
			Semanticdb.Type semanticdbType = generateType(superType);
			if (semanticdbType == null) {
				semanticdbType = UNRESOLVED_TYPE_REF;
			}
			builder.addParents(semanticdbType);
		}

		builder.setDeclarations(generateScope(sym.getEnclosedElements()));

		return signature(builder);
	}

	private Signature generateMethodSignature(ExecutableElement sym) {
		MethodSignature.Builder builder = MethodSignature.newBuilder();

		builder.setTypeParameters(generateScope(sym.getTypeParameters()));

		builder.addParameterLists(generateScope(sym.getParameters()));

		Semanticdb.Type returnType = generateType(sym.getReturnType());
		if (returnType != null) {
			builder.setReturnType(returnType);
		}

		List<Semanticdb.Type> thrownTypes = sym.getThrownTypes().stream().map(this::generateType)
				.collect(Collectors.toList());
		builder.addAllThrows(thrownTypes);

		return signature(builder);
	}

	private Signature generateFieldSignature(VariableElement sym) {
		Semanticdb.Type generateType = generateType(sym.asType());
		if (generateType == null) {
			generateType = UNRESOLVED_TYPE_REF;
		}
		return signature(ValueSignature.newBuilder().setTpe(generateType));
	}

	private Signature generateTypeSignature(TypeParameterElement sym) {
		TypeSignature.Builder builder = TypeSignature.newBuilder();

		if (sym instanceof TypeElement) {
			builder.setTypeParameters(generateScope(((TypeElement) sym).getTypeParameters()));
		}

		TypeMirror varType = sym.asType();
		if (varType instanceof TypeVariable) {
			Semanticdb.Type upperBound = generateType(((TypeVariable) varType).getUpperBound());
			if (upperBound != null)
				builder.setUpperBound(upperBound);
			else
				builder.setUpperBound(UNRESOLVED_TYPE_REF);
		} else
			builder.setUpperBound(UNRESOLVED_TYPE_REF);

		return signature(builder);
	}

	private Scope generateScope(List<? extends Element> elements) {
		Scope.Builder scope = Scope.newBuilder();
		for (Element typeVar : elements) {
			scope.addSymlinks(cache.semanticdbSymbol(typeVar, locals));
		}
		return scope.build();
	}

	private Semanticdb.Type generateType(TypeMirror mirror) {
		return new SemanticdbTypeVisitor(cache, locals, types).semanticdbType(mirror);
	}
}
