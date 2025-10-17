package scala.meta.internal.semanticdb.javac;

import javax.lang.model.element.Element;
import javax.lang.model.util.SimpleTypeVisitor8;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.NoType;
import javax.lang.model.util.Types;
import java.util.ArrayList;

import static scala.meta.internal.jsemanticdb.SemanticdbBuilders.*;
import scala.meta.internal.jsemanticdb.Semanticdb;

/**
 * A TypeMirror tree visitor that constructs a recursive SemanticDB Type
 * structure.
 */
class SemanticdbTypeVisitor extends SimpleTypeVisitor8<Semanticdb.Type, Void> {
	static final Semanticdb.Type UNRESOLVED_TYPE_REF = typeRef("unresolved_type#");

	static final String ARRAY_SYMBOL = "scala/Array#";

	private final GlobalSymbolsCache cache;
	private final LocalSymbolsCache locals;
	private final Types types;

	SemanticdbTypeVisitor(GlobalSymbolsCache cache, LocalSymbolsCache locals, Types types) {
		this.cache = cache;
		this.locals = locals;
		this.types = types;
	}

	public Semanticdb.Type semanticdbType(TypeMirror tpe) {
		Semanticdb.Type result = super.visit(tpe);
		return result == null ? UNRESOLVED_TYPE_REF : result;
	}

	@Override
	public Semanticdb.Type visitDeclared(DeclaredType t, Void unused) {
		boolean isExistential = t.getTypeArguments().stream().anyMatch((type) -> type instanceof WildcardType);

		ArrayList<Semanticdb.Type> typeParams = new ArrayList<>();
		Semanticdb.Scope.Builder declarations = Semanticdb.Scope.newBuilder();
		for (TypeMirror type : t.getTypeArguments()) {
			typeParams.add(semanticdbType(type));

			if (type instanceof WildcardType) {
				Semanticdb.TypeSignature.Builder typeSig = Semanticdb.TypeSignature.newBuilder();
				WildcardType wildcardType = (WildcardType) type;

				// semanticdb spec asks for List() not None for type_parameters field
				typeSig.setTypeParameters(Semanticdb.Scope.newBuilder());

				if (wildcardType.getExtendsBound() != null) {
					typeSig.setUpperBound(super.visit(wildcardType.getExtendsBound()));
				} else if (wildcardType.getSuperBound() != null) {
					typeSig.setLowerBound(super.visit(wildcardType.getSuperBound()));
				}

				declarations.addHardlinks(Semanticdb.SymbolInformation.newBuilder().setSymbol("local_wildcard")
						.setSignature(Semanticdb.Signature.newBuilder().setTypeSignature(typeSig)));
			} else {
				Element element = types.asElement(type);
				declarations.addSymlinks(cache.semanticdbSymbol(element, locals));
			}
		}

		if (!isExistential) {
			return typeRef(cache.semanticdbSymbol(t.asElement(), locals), typeParams);
		} else {
			return existentialType(typeRef(cache.semanticdbSymbol(t.asElement(), locals), typeParams),
					declarations.build());
		}
	}

	@Override
	public Semanticdb.Type visitArray(ArrayType t, Void unused) {
		ArrayList<Semanticdb.Type> types = new ArrayList<Semanticdb.Type>();
		types.add(semanticdbType(t.getComponentType()));
		return typeRef(ARRAY_SYMBOL, types);
	}

	@Override
	public Semanticdb.Type visitPrimitive(PrimitiveType t, Void unused) {
		return typeRef(primitiveSymbol(t.getKind()));
	}

	@Override
	public Semanticdb.Type visitTypeVariable(TypeVariable t, Void unused) {
		return typeRef(cache.semanticdbSymbol(t.asElement(), locals));
	}

	@Override
	public Semanticdb.Type visitIntersection(IntersectionType t, Void unused) {
		ArrayList<Semanticdb.Type> types = new ArrayList<>();
		for (TypeMirror type : t.getBounds()) {
			types.add(super.visit(type));
		}

		return intersectionType(types);
	}

	@Override
	public Semanticdb.Type visitWildcard(WildcardType t, Void unused) {
		// https://github.com/scalameta/scalameta/issues/1703
		// https://sourcegraph.com/github.com/scalameta/scalameta/-/blob/semanticdb/metacp/src/main/scala/scala/meta/internal/javacp/Javacp.scala#L452:19
		return typeRef("local_wildcard");
	}

	@Override
	public Semanticdb.Type visitNoType(NoType t, Void unused) {
		return typeRef(primitiveSymbol(t.getKind()));
	}

	public String primitiveSymbol(TypeKind kind) {
		switch (kind) {
		case BOOLEAN:
			return "scala/Boolean#";
		case BYTE:
			return "scala/Byte#";
		case SHORT:
			return "scala/Short#";
		case INT:
			return "scala/Int#";
		case LONG:
			return "scala/Long#";
		case CHAR:
			return "scala/Char#";
		case FLOAT:
			return "scala/Float#";
		case DOUBLE:
			return "scala/Double#";
		case VOID:
			return "scala/Unit#";
		default:
			throw new IllegalArgumentException("got " + kind.name());
		}
	}
}
