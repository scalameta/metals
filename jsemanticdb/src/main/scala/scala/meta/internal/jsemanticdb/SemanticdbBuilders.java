package scala.meta.internal.jsemanticdb;

import java.util.List;

public class SemanticdbBuilders {
  // SemanticDB Types
  public static Semanticdb.Type typeRef(String symbol) {
    return Semanticdb.Type.newBuilder()
        .setTypeRef(Semanticdb.TypeRef.newBuilder().setSymbol(symbol))
        .build();
  }

  public static Semanticdb.Type typeRef(String symbol, List<Semanticdb.Type> typeArguments) {
    return Semanticdb.Type.newBuilder()
        .setTypeRef(
            Semanticdb.TypeRef.newBuilder().setSymbol(symbol).addAllTypeArguments(typeArguments))
        .build();
  }

  public static Semanticdb.Type existentialType(
      Semanticdb.Type type, Semanticdb.Scope declarations) {
    return Semanticdb.Type.newBuilder()
        .setExistentialType(
            Semanticdb.ExistentialType.newBuilder().setTpe(type).setDeclarations(declarations))
        .build();
  }

  public static Semanticdb.Type intersectionType(List<? extends Semanticdb.Type> types) {
    return Semanticdb.Type.newBuilder()
        .setIntersectionType(Semanticdb.IntersectionType.newBuilder().addAllTypes(types))
        .build();
  }

  // SemanticDB Signatures

  public static Semanticdb.Signature signature(Semanticdb.ClassSignature.Builder signature) {
    return Semanticdb.Signature.newBuilder().setClassSignature(signature).build();
  }

  public static Semanticdb.Signature signature(Semanticdb.MethodSignature.Builder signature) {
    return Semanticdb.Signature.newBuilder().setMethodSignature(signature).build();
  }

  public static Semanticdb.Signature signature(Semanticdb.ValueSignature.Builder signature) {
    return Semanticdb.Signature.newBuilder().setValueSignature(signature).build();
  }

  public static Semanticdb.Signature signature(Semanticdb.TypeSignature.Builder signature) {
    return Semanticdb.Signature.newBuilder().setTypeSignature(signature).build();
  }

  // SemanticDB Symbols

  public static Semanticdb.SymbolOccurrence symbolOccurrence(
      String symbol, Semanticdb.Range range, Semanticdb.SymbolOccurrence.Role role) {
    return Semanticdb.SymbolOccurrence.newBuilder()
        .setSymbol(symbol)
        .setRange(range)
        .setRole(role)
        .build();
  }

  public static Semanticdb.SymbolInformation.Builder symbolInformation(String symbol) {
    return Semanticdb.SymbolInformation.newBuilder().setSymbol(symbol);
  }

  // SemanticDB Access

  public static Semanticdb.Access privateAccess() {
    return Semanticdb.Access.newBuilder()
        .setPrivateAccess(Semanticdb.PrivateAccess.newBuilder())
        .build();
  }

  public static Semanticdb.Access publicAccess() {
    return Semanticdb.Access.newBuilder()
        .setPublicAccess(Semanticdb.PublicAccess.newBuilder())
        .build();
  }

  public static Semanticdb.Access protectedAccess() {
    return Semanticdb.Access.newBuilder()
        .setProtectedAccess(Semanticdb.ProtectedAccess.newBuilder())
        .build();
  }

  public static Semanticdb.Access privateWithinAccess(String symbol) {
    return Semanticdb.Access.newBuilder()
        .setPrivateWithinAccess(Semanticdb.PrivateWithinAccess.newBuilder().setSymbol(symbol))
        .build();
  }

  // SemanticDB Trees

  public static Semanticdb.Tree tree(Semanticdb.IdTree idTree) {
    return Semanticdb.Tree.newBuilder().setIdTree(idTree).build();
  }

  public static Semanticdb.IdTree idTree(String symbol) {
    return Semanticdb.IdTree.newBuilder().setSymbol(symbol).build();
  }

  public static Semanticdb.Tree tree(Semanticdb.ApplyTree applyTree) {
    return Semanticdb.Tree.newBuilder().setApplyTree(applyTree).build();
  }

  public static Semanticdb.ApplyTree applyTree(
      Semanticdb.Tree function, Iterable<Semanticdb.Tree> arguments) {
    return Semanticdb.ApplyTree.newBuilder()
        .setFunction(function)
        .addAllArguments(arguments)
        .build();
  }

  public static Semanticdb.Tree tree(Semanticdb.SelectTree selectTree) {
    return Semanticdb.Tree.newBuilder().setSelectTree(selectTree).build();
  }

  public static Semanticdb.SelectTree selectTree(
      Semanticdb.Tree qualifier, Semanticdb.IdTree idTree) {
    return Semanticdb.SelectTree.newBuilder().setQualifier(qualifier).setId(idTree).build();
  }

  public static Semanticdb.Tree tree(Semanticdb.LiteralTree literalTree) {
    return Semanticdb.Tree.newBuilder().setLiteralTree(literalTree).build();
  }

  public static Semanticdb.LiteralTree literalTree(Semanticdb.Constant constant) {
    return Semanticdb.LiteralTree.newBuilder().setConstant(constant).build();
  }

  public static Semanticdb.Tree tree(Semanticdb.AnnotationTree annotationTree) {
    return Semanticdb.Tree.newBuilder().setAnnotationTree(annotationTree).build();
  }

  public static Semanticdb.Tree tree(Semanticdb.BinaryOperatorTree binaryOperatorTree) {
    return Semanticdb.Tree.newBuilder().setBinopTree(binaryOperatorTree).build();
  }

  public static Semanticdb.BinaryOperatorTree binopTree(
      Semanticdb.Tree lhs, Semanticdb.BinaryOperator operator, Semanticdb.Tree rhs) {
    return Semanticdb.BinaryOperatorTree.newBuilder()
        .setLhs(lhs)
        .setOp(operator)
        .setRhs(rhs)
        .build();
  }

  public static Semanticdb.Tree tree(Semanticdb.UnaryOperatorTree unaryOperatorTree) {
    return Semanticdb.Tree.newBuilder().setUnaryopTree(unaryOperatorTree).build();
  }

  public static Semanticdb.Tree tree(Semanticdb.CastTree castTree) {
    return Semanticdb.Tree.newBuilder().setCastTree(castTree).build();
  }

  public static Semanticdb.UnaryOperatorTree unaryOpTree(
      Semanticdb.UnaryOperator operator, Semanticdb.Tree rhs) {
    return Semanticdb.UnaryOperatorTree.newBuilder().setOp(operator).setTree(rhs).build();
  }

  public static Semanticdb.Tree tree(Semanticdb.AssignTree assignTree) {
    return Semanticdb.Tree.newBuilder().setAssignTree(assignTree).build();
  }

  public static Semanticdb.AssignTree assignTree(Semanticdb.Tree lhs, Semanticdb.Tree rhs) {
    return Semanticdb.AssignTree.newBuilder().setLhs(lhs).setRhs(rhs).build();
  }

  public static Semanticdb.CastTree castTree(Semanticdb.Type type, Semanticdb.Tree value) {
    return Semanticdb.CastTree.newBuilder().setTpe(type).setValue(value).build();
  }

  public static Semanticdb.AnnotationTree annotationTree(
      Semanticdb.Type type, Iterable<Semanticdb.Tree> parameters) {
    return Semanticdb.AnnotationTree.newBuilder().setTpe(type).addAllParameters(parameters).build();
  }

  // SemanticDB Constants

  public static Semanticdb.Constant stringConst(String value) {
    return Semanticdb.Constant.newBuilder()
        .setStringConstant(Semanticdb.StringConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant doubleConst(Double value) {
    return Semanticdb.Constant.newBuilder()
        .setDoubleConstant(Semanticdb.DoubleConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant nullConst() {
    return Semanticdb.Constant.newBuilder()
        .setNullConstant(Semanticdb.NullConstant.newBuilder())
        .build();
  }

  public static Semanticdb.Constant floatConst(Float value) {
    return Semanticdb.Constant.newBuilder()
        .setFloatConstant(Semanticdb.FloatConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant longConst(Long value) {
    return Semanticdb.Constant.newBuilder()
        .setLongConstant(Semanticdb.LongConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant intConst(Integer value) {
    return Semanticdb.Constant.newBuilder()
        .setIntConstant(Semanticdb.IntConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant charConst(Character value) {
    return Semanticdb.Constant.newBuilder()
        .setCharConstant(Semanticdb.CharConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant shortConst(Short value) {
    return Semanticdb.Constant.newBuilder()
        .setShortConstant(Semanticdb.ShortConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant byteConst(Byte value) {
    return Semanticdb.Constant.newBuilder()
        .setByteConstant(Semanticdb.ByteConstant.newBuilder().setValue(value))
        .build();
  }

  public static Semanticdb.Constant booleanConst(Boolean value) {
    return Semanticdb.Constant.newBuilder()
        .setBooleanConstant(Semanticdb.BooleanConstant.newBuilder().setValue(value))
        .build();
  }
}
