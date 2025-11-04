package scala.meta.internal.semanticdb.javac;

import static scala.meta.internal.jsemanticdb.SemanticdbBuilders.*;
import static scala.meta.internal.semanticdb.javac.SemanticdbTypeVisitor.ARRAY_SYMBOL;

import com.sun.source.tree.*;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import scala.meta.internal.jsemanticdb.Semanticdb;

public class SemanticdbTrees {
  public SemanticdbTrees(
      GlobalSymbolsCache globals,
      LocalSymbolsCache locals,
      String semanticdbUri,
      Types types,
      Trees trees,
      HashMap<Tree, TreePath> nodes) {
    this.globals = globals;
    this.locals = locals;
    this.semanticdbUri = semanticdbUri;
    this.types = types;
    this.trees = trees;
    this.nodes = nodes;
    this.typeVisitor = new SemanticdbTypeVisitor(globals, locals, types);
  }

  private final GlobalSymbolsCache globals;
  private final LocalSymbolsCache locals;
  private final String semanticdbUri;
  private final Types types;
  private final Trees trees;
  private final HashMap<Tree, TreePath> nodes;
  private final SemanticdbTypeVisitor typeVisitor;

  public List<Semanticdb.AnnotationTree> annotations(Tree node) {
    if (!(node instanceof ClassTree)
        && !(node instanceof MethodTree)
        && !(node instanceof VariableTree)) return null;

    List<Semanticdb.AnnotationTree> annotations = new ArrayList<>();

    ModifiersTree mods;
    if (node instanceof ClassTree) {
      mods = ((ClassTree) node).getModifiers();
    } else if (node instanceof MethodTree) {
      mods = ((MethodTree) node).getModifiers();
    } else {
      mods = ((VariableTree) node).getModifiers();
    }

    for (AnnotationTree annotation : mods.getAnnotations()) {
      if (annotation == null) {
        // Can happen when compiling invalid code
        continue;
      }
      annotations.add(annotationBuilder(annotation));
    }

    return annotations;
  }

  public Semanticdb.AnnotationTree annotationBuilder(AnnotationTree annotation) {
    ArrayList<Semanticdb.Tree> params = new ArrayList<>(annotation.getArguments().size());

    for (ExpressionTree param : annotation.getArguments()) {
      // anecdotally not always AssignmentTree in some situations when a compilation
      // unit can't
      // resolve symbols fully
      if (param instanceof AssignmentTree) {
        AssignmentTree assign = (AssignmentTree) param;
        ExpressionTree assignValue = assign.getExpression();
        TreePath variableTreePath = nodes.get(assign.getVariable());
        if (variableTreePath != null) {
          Element variableSym = trees.getElement(variableTreePath);
          String symbol = globals.semanticdbSymbol(variableSym, locals);
          params.add(tree(assignTree(tree(idTree(symbol)), annotationParameter(assignValue))));
        }
      } else {
        params.add(annotationParameter(param));
      }
    }

    TreePath annotationTreePath = nodes.get(annotation);
    Element annotationSym = trees.getElement(annotationTreePath);

    Semanticdb.Type type = typeVisitor.semanticdbType(annotationSym.asType());
    return annotationTree(type, params);
  }

  private TypeMirror getTreeType(Tree tree) {
    TreePath path = nodes.get(tree);
    return trees.getTypeMirror(path);
  }

  private Semanticdb.Tree annotationParameter(ExpressionTree expr) {
    if (expr instanceof MemberSelectTree) {
      TreePath expressionTreePath = nodes.get(expr);
      Element expressionSym = trees.getElement(expressionTreePath);
      return tree(
          selectTree(
              tree(idTree(globals.semanticdbSymbol(expressionSym.getEnclosingElement(), locals))),
              idTree(globals.semanticdbSymbol(expressionSym, locals))));
    } else if (expr instanceof NewArrayTree) {
      NewArrayTree rhs = (NewArrayTree) expr;
      return tree(
          applyTree(
              tree(idTree(ARRAY_SYMBOL)),
              rhs.getInitializers().stream()
                  .map(this::annotationParameter)
                  .collect(Collectors.toList())));
    } else if (expr instanceof LiteralTree) {
      // Literals can either be a primitive or String
      Object value = ((LiteralTree) expr).getValue();
      final Semanticdb.Constant constant;
      // Technically, annotation parameter values cannot be null,
      // according to JLS:
      // https://docs.oracle.com/javase/specs/jls/se8/html/jls-9.html#jls-9.7.1
      // But this codepath is still possible to hit when compiling invalid code - and
      // we should handle the null const case in order to fail more gracefully
      if (value == null) constant = nullConst();
      else if (value instanceof String) constant = stringConst((String) value);
      else if (value instanceof Boolean) constant = booleanConst((Boolean) value);
      else if (value instanceof Byte) constant = byteConst((Byte) value);
      else if (value instanceof Short) constant = shortConst((Short) value);
      else if (value instanceof Integer) constant = intConst((Integer) value);
      else if (value instanceof Long) constant = longConst((Long) value);
      else if (value instanceof Character) constant = charConst((Character) value);
      else if (value instanceof Float) constant = floatConst((Float) value);
      else if (value instanceof Double) constant = doubleConst((Double) value);
      else
        throw new IllegalStateException(
            semanticdbUri
                + ": annotation parameter rhs was of unexpected class type "
                + value.getClass()
                + "\n"
                + value);
      return tree(literalTree(constant));
    } else if (expr instanceof AnnotationTree) {
      return tree(annotationBuilder((AnnotationTree) expr));
    } else if (expr instanceof IdentifierTree) {
      TreePath expressionTreePath = nodes.get(expr);
      Element expressionSym = trees.getElement(expressionTreePath);
      return tree(idTree(globals.semanticdbSymbol(expressionSym, locals)));
    } else if (expr instanceof BinaryTree) {
      BinaryTree binExpr = (BinaryTree) expr;
      return tree(
          binopTree(
              annotationParameter(binExpr.getLeftOperand()),
              semanticdbBinaryOperator(expr.getKind()),
              annotationParameter(binExpr.getRightOperand())));
    } else if (expr instanceof UnaryTree) {
      UnaryTree unaryExpr = (UnaryTree) expr;
      return tree(
          unaryOpTree(
              semanticdbUnaryOperator(unaryExpr.getKind()),
              annotationParameter(unaryExpr.getExpression())));
    } else if (expr instanceof ParenthesizedTree) {
      ParenthesizedTree parenExpr = (ParenthesizedTree) expr;
      return annotationParameter(parenExpr.getExpression());
    } else if (expr instanceof TypeCastTree) {
      TypeCastTree tree = (TypeCastTree) expr;
      return tree(
          castTree(
              typeVisitor.semanticdbType(getTreeType(tree.getType())),
              annotationParameter(tree.getExpression())));
    } else {
      throw new IllegalArgumentException(
          semanticdbUri
              + ": annotation parameter rhs was of unexpected tree node type "
              + expr.getClass()
              + "\n"
              + expr);
    }
  }

  private Semanticdb.BinaryOperator semanticdbBinaryOperator(Tree.Kind kind) {
    switch (kind) {
      case PLUS:
        return Semanticdb.BinaryOperator.PLUS;
      case MINUS:
        return Semanticdb.BinaryOperator.MINUS;
      case MULTIPLY:
        return Semanticdb.BinaryOperator.MULTIPLY;
      case DIVIDE:
        return Semanticdb.BinaryOperator.DIVIDE;
      case REMAINDER:
        return Semanticdb.BinaryOperator.REMAINDER;
      case LESS_THAN:
        return Semanticdb.BinaryOperator.LESS_THAN;
      case GREATER_THAN:
        return Semanticdb.BinaryOperator.GREATER_THAN;
      case LEFT_SHIFT:
        return Semanticdb.BinaryOperator.SHIFT_LEFT;
      case RIGHT_SHIFT:
        return Semanticdb.BinaryOperator.SHIFT_RIGHT;
      case UNSIGNED_RIGHT_SHIFT:
        return Semanticdb.BinaryOperator.SHIFT_RIGHT_UNSIGNED;
      case EQUAL_TO:
        return Semanticdb.BinaryOperator.EQUAL_TO;
      case NOT_EQUAL_TO:
        return Semanticdb.BinaryOperator.NOT_EQUAL_TO;
      case LESS_THAN_EQUAL:
        return Semanticdb.BinaryOperator.LESS_THAN_EQUAL;
      case GREATER_THAN_EQUAL:
        return Semanticdb.BinaryOperator.GREATER_THAN_EQUAL;
      case CONDITIONAL_AND:
        return Semanticdb.BinaryOperator.CONDITIONAL_AND;
      case CONDITIONAL_OR:
        return Semanticdb.BinaryOperator.CONDITIONAL_OR;
      case AND:
        return Semanticdb.BinaryOperator.AND;
      case OR:
        return Semanticdb.BinaryOperator.OR;
      case XOR:
        return Semanticdb.BinaryOperator.XOR;
      default:
        throw new IllegalStateException(
            semanticdbUri + ": unexpected binary expression operator kind " + kind);
    }
  }

  private Semanticdb.UnaryOperator semanticdbUnaryOperator(Tree.Kind kind) {
    switch (kind) {
      case UNARY_MINUS:
        return Semanticdb.UnaryOperator.UNARY_MINUS;

      case UNARY_PLUS:
        return Semanticdb.UnaryOperator.UNARY_PLUS;

      case POSTFIX_INCREMENT:
        return Semanticdb.UnaryOperator.UNARY_POSTFIX_INCREMENT;

      case POSTFIX_DECREMENT:
        return Semanticdb.UnaryOperator.UNARY_POSTFIX_DECREMENT;

      case PREFIX_INCREMENT:
        return Semanticdb.UnaryOperator.UNARY_PREFIX_INCREMENT;

      case PREFIX_DECREMENT:
        return Semanticdb.UnaryOperator.UNARY_PREFIX_DECREMENT;

      case BITWISE_COMPLEMENT:
        return Semanticdb.UnaryOperator.UNARY_BITWISE_COMPLEMENT;

      case LOGICAL_COMPLEMENT:
        return Semanticdb.UnaryOperator.UNARY_LOGICAL_COMPLEMENT;

      default:
        throw new IllegalStateException(
            semanticdbUri + ": unexpected unary expression operator kind " + kind);
    }
  }
}
