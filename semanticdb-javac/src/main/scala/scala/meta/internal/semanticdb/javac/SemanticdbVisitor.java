package scala.meta.internal.semanticdb.javac;

import static scala.meta.internal.jsemanticdb.SemanticdbBuilders.*;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.meta.internal.jsemanticdb.Semanticdb;
import scala.meta.internal.jsemanticdb.Semanticdb.SymbolInformation.Kind;
import scala.meta.internal.jsemanticdb.Semanticdb.SymbolInformation.Property;
import scala.meta.internal.jsemanticdb.Semanticdb.SymbolOccurrence.Role;

/** Walks the AST of a typechecked compilation unit and generates a SemanticDB TextDocument. */
public class SemanticdbVisitor extends TreePathScanner<Void, Void> {

  private final Logger logger = LoggerFactory.getLogger(SemanticdbVisitor.class);
  private final GlobalSymbolsCache globals;
  private final LocalSymbolsCache locals;
  private final Types types;
  private final Trees trees;
  private final SourcePositions sourcePositions;
  private final CompilationUnitTree compUnitTree;
  private final Elements elements;
  private final SemanticdbJavacOptions options;
  private final ArrayList<Semanticdb.SymbolOccurrence> occurrences;
  private final ArrayList<Semanticdb.SymbolInformation> symbolInfos;
  private String source;
  public String uri;
  private final LinkedHashMap<Tree, TreePath> nodes;

  private boolean skipTreesOutsideTargetRange = false;
  private long targetStartOffset = Long.MIN_VALUE;
  private long targetEndOffset = Long.MAX_VALUE;

  /** Only emit symbol occurrences that are within the target offset range. */
  public void skipTreesOutsideTargetRange(long start, long end) {
    this.skipTreesOutsideTargetRange = true;
    this.targetStartOffset = start;
    this.targetEndOffset = end;
  }

  public SemanticdbVisitor(
      JavacTask task,
      GlobalSymbolsCache globals,
      SemanticdbJavacOptions options,
      CompilationUnitTree compUnitTree) {
    this(globals, compUnitTree, options, task.getTypes(), Trees.instance(task), task.getElements());
  }

  public SemanticdbVisitor(
      GlobalSymbolsCache globals,
      CompilationUnitTree compUnitTree,
      SemanticdbJavacOptions options,
      Types types,
      Trees trees,
      Elements elements) {
    this.globals = globals; // Reused cache between compilation units.
    this.locals = new LocalSymbolsCache(); // Fresh cache per compilation unit.
    this.options = options;
    this.types = types;
    this.elements = elements;
    this.trees = trees;
    this.sourcePositions = trees.getSourcePositions();
    this.compUnitTree = compUnitTree;
    this.occurrences = new ArrayList<>();
    this.symbolInfos = new ArrayList<>();
    this.source = semanticdbText();
    this.uri = semanticdbUri(compUnitTree, options);
    this.nodes = new LinkedHashMap<>();
  }

  public Semanticdb.TextDocument buildTextDocument(CompilationUnitTree tree) {
    return buildTextDocumentBuilder(tree).build();
  }

  public Semanticdb.TextDocument.Builder buildTextDocumentBuilder(CompilationUnitTree tree) {
    this.scan(tree, null); // Trigger recursive AST traversal to collect SemanticDB information.

    resolveNodes();

    return Semanticdb.TextDocument.newBuilder()
        .setSchema(Semanticdb.Schema.SEMANTICDB4)
        .setLanguage(Semanticdb.Language.JAVA)
        .setUri(uri)
        .setText(options.includeText ? this.source : "")
        .setMd5(semanticdbMd5())
        .addAllOccurrences(occurrences)
        .addAllSymbols(symbolInfos);
  }

  private Optional<Semanticdb.Range> emitSymbolOccurrence(
      Element sym, Tree tree, Name name, Role role, CompilerRange kind) {
    if (sym == null || name == null) return Optional.empty();
    Optional<Semanticdb.Range> range = semanticdbRange(tree, kind, sym, name.toString());
    emitSymbolOccurrence(sym, range, role);
    if (role == Role.DEFINITION) {
      // Only emit SymbolInformation for symbols that are defined in this compilation
      // unit.
      emitSymbolInformation(sym, tree);
    }
    return range;
  }

  private void emitSymbolOccurrence(Element sym, Optional<Semanticdb.Range> range, Role role) {
    if (sym == null) return;
    Optional<Semanticdb.SymbolOccurrence> occ = semanticdbOccurrence(sym, range, role);
    occ.ifPresent(occurrences::add);
  }

  private void emitSymbolInformation(Element sym, Tree tree) {
    String symbol = semanticdbSymbol(sym);
    Semanticdb.SymbolInformation.Builder builder = symbolInformation(symbol);
    Semanticdb.Documentation documentation = semanticdbDocumentation(tree);
    if (documentation != null) builder.setDocumentation(documentation);
    Semanticdb.Signature signature = semanticdbSignature(sym);
    if (signature != null) builder.setSignature(signature);
    if (SemanticdbSymbols.isLocal(symbol)) {
      String enclosingSymbol = semanticdbSymbol(sym.getEnclosingElement());
      if (enclosingSymbol != null) builder.setEnclosingSymbol(enclosingSymbol);
    }

    List<Semanticdb.AnnotationTree> annotations =
        new SemanticdbTrees(globals, locals, uri, types, trees, nodes).annotations(tree);
    if (annotations != null) builder.addAllAnnotations(annotations);

    builder
        .setProperties(semanticdbSymbolInfoProperties(sym))
        .setDisplayName(sym.getSimpleName().toString())
        .setAccess(semanticdbAccess(sym));

    switch (sym.getKind()) {
      case ENUM:
      case CLASS:
        builder.setKind(Kind.CLASS);
        builder.addAllOverriddenSymbols(semanticdbParentSymbols((TypeElement) sym));
        break;
      case INTERFACE:
      case ANNOTATION_TYPE:
        builder.setKind(Kind.INTERFACE);
        builder.addAllOverriddenSymbols(semanticdbParentSymbols((TypeElement) sym));
        break;
      case FIELD:
        builder.setKind(Kind.FIELD);
        break;
      case METHOD:
        builder.setKind(Kind.METHOD);
        builder.addAllOverriddenSymbols(
            semanticdbOverrides(
                (ExecutableElement) sym, sym.getEnclosingElement(), new HashSet<>()));
        break;
      case CONSTRUCTOR:
        builder.setKind(Kind.CONSTRUCTOR);
        break;
      case TYPE_PARAMETER:
        builder.setKind(Kind.TYPE_PARAMETER);
        break;
      case ENUM_CONSTANT: // overwrite previous value here
        String args =
            ((NewClassTree) ((VariableTree) tree).getInitializer())
                .getArguments().stream()
                    .map(ExpressionTree::toString)
                    .collect(Collectors.joining(", "));
        if (!args.isEmpty())
          builder.setDisplayName(sym.getSimpleName().toString() + "(" + args + ")");
        break;
      case LOCAL_VARIABLE:
        builder.setKind(Kind.LOCAL);
        break;
    }

    Semanticdb.SymbolInformation info = builder.build();

    symbolInfos.add(info);
  }

  void resolveNodes() {
    // ignore parts of NewClassTree. It would cause references to classes in
    // addition to references
    // to constructors. In these cases, the references to classes aren't wanted
    HashSet<Tree> ignoreNodes = new HashSet<>();
    for (Tree node : nodes.keySet())
      if (node instanceof NewClassTree) {
        NewClassTree newClassTree = (NewClassTree) node;
        if (newClassTree.getClassBody() == null) {
          if (newClassTree.getIdentifier() instanceof ParameterizedTypeTree) {
            ParameterizedTypeTree paramNode = (ParameterizedTypeTree) newClassTree.getIdentifier();
            ignoreNodes.add(paramNode.getType());
          }
          ignoreNodes.add(newClassTree.getIdentifier());
        }
      }

    for (Map.Entry<Tree, TreePath> entry : nodes.entrySet()) {
      Tree node = entry.getKey();
      if (!ignoreNodes.contains(node)) {
        if (node instanceof TypeParameterTree) {
          resolveTypeParameterTree((TypeParameterTree) node, entry.getValue());
        } else if (node instanceof ClassTree) {
          resolveClassTree((ClassTree) node, entry.getValue());
        } else if (node instanceof MethodTree) {
          resolveMethodTree((MethodTree) node, entry.getValue());
        } else if (node instanceof VariableTree) {
          resolveVariableTree((VariableTree) node, entry.getValue());
        } else if (node instanceof IdentifierTree) {
          resolveIdentifierTree((IdentifierTree) node, entry.getValue());
        } else if (node instanceof MemberReferenceTree) {
          resolveMemberReferenceTree((MemberReferenceTree) node, entry.getValue());
        } else if (node instanceof MemberSelectTree) {
          resolveMemberSelectTree((MemberSelectTree) node, entry.getValue());
        } else if (node instanceof NewClassTree) {
          resolveNewClassTree((NewClassTree) node, entry.getValue());
        }
      }
    }
  }

  private boolean isOffsetInTargetRange(long start, long end, long offset) {
    return offset >= start && offset <= end;
  }

  private boolean doesTreeEncloseTargetRange(Tree tree) {
    if (!skipTreesOutsideTargetRange) {
      return true;
    }
    var start = this.sourcePositions.getStartPosition(compUnitTree, tree);
    var end = this.sourcePositions.getEndPosition(compUnitTree, tree);
    return isOffsetInTargetRange(start, end, targetStartOffset)
        || isOffsetInTargetRange(start, end, targetEndOffset);
  }

  // =======================================
  // Overridden methods from TreePathScanner
  // =======================================
  @Override
  public Void scan(Tree tree, Void unused) {
    // System.out.println("scanning tree: " + tree);
    if (tree != null) {
      if (!doesTreeEncloseTargetRange(tree)) {
        // Skip trees outside the target offset range. This only
        // happens when we're doing a partial semanticdb request.
        return null;
      }
      TreePath path = new TreePath(getCurrentPath(), tree);
      nodes.put(tree, path);
    }
    try {
      return super.scan(tree, unused);
    } catch (AssertionError ignored) {
      logger.warn(
          String.format("AssertionError ignored: %s at tree %s", ignored.getMessage(), tree));
      // The compiler may throw assertion errors for invalid code. Example:
      // java.lang.AssertionError: unknown literal kind BYTE
      // jdk.compiler/com.sun.tools.javac.code.TypeTag.getKindLiteral(TypeTag.java:215)
      // jdk.compiler/com.sun.tools.javac.tree.JCTree$JCLiteral.getKind(JCTree.java:2616)
      // scala.meta.internal.semanticdb.javac.SemanticdbVisitor.scan(SemanticdbVisitor.java:238)
      return unused;
    }
  }

  private boolean isAnonymous(Element sym) {
    return sym.getSimpleName().length() == 0;
  }

  public static <A extends String, B> B bar(A paramA, B paramB) {
    return paramB;
  }

  private void resolveClassTree(ClassTree node, TreePath treePath) {
    Element sym = trees.getElement(treePath);
    if (sym != null && sym.getSimpleName().length() > 0) {
      emitSymbolOccurrence(
          sym,
          node,
          sym.getSimpleName(),
          Role.DEFINITION,
          CompilerRange.FROM_POINT_WITH_TEXT_SEARCH);
    }
  }

  private void resolveTypeParameterTree(TypeParameterTree node, TreePath treePath) {
    Element sym = trees.getElement(treePath);
    if (sym != null && sym.getSimpleName().length() > 0) {
      emitSymbolOccurrence(
          sym,
          node,
          sym.getSimpleName(),
          Role.DEFINITION,
          CompilerRange.FROM_POINT_WITH_TEXT_SEARCH);
    }
  }

  private void resolveMethodTree(MethodTree node, TreePath treePath) {
    Element sym = trees.getElement(treePath);
    if (sym != null) {
      Element enclosingElement = sym.getEnclosingElement();
      if (sym.getKind() != ElementKind.CONSTRUCTOR || !isAnonymous(enclosingElement)) {
        Name name;
        if (sym.getKind() == ElementKind.CONSTRUCTOR) name = enclosingElement.getSimpleName();
        else name = sym.getSimpleName();

        emitSymbolOccurrence(
            sym, node, name, Role.DEFINITION, CompilerRange.FROM_POINT_WITH_TEXT_SEARCH);
      }
    }
  }

  private void resolveVariableTree(VariableTree node, TreePath treePath) {
    Element sym = trees.getElement(treePath);
    if (sym != null) {
      Optional<Semanticdb.Range> range =
          emitSymbolOccurrence(
              sym,
              node,
              sym.getSimpleName(),
              Role.DEFINITION,
              CompilerRange.FROM_POINT_WITH_TEXT_SEARCH);
      if (sym.getKind() == ElementKind.ENUM_CONSTANT) {
        TreePath typeTreePath = nodes.get(node.getInitializer());
        Element typeSym = trees.getElement(typeTreePath);
        if (typeSym != null) emitSymbolOccurrence(typeSym, range, Role.REFERENCE);
      }
    }
  }

  private void resolveIdentifierTree(IdentifierTree node, TreePath treePath) {
    Name nodeName = node.getName();
    if (nodeName != null) {
      Element sym = trees.getElement(treePath);
      if (sym != null) {
        boolean isThis = nodeName.toString().equals("this");
        boolean isSuper = !isThis && nodeName.toString().equals("super");
        // exclude `this.` references but include `this(` and `super(` references
        if (((sym.getKind() == ElementKind.CONSTRUCTOR) == isThis) || (isSuper)) {
          TreePath parentPath = treePath.getParentPath();
          Element parentSym = trees.getElement(parentPath);
          if (parentSym == null || parentSym.getKind() != null) {
            emitSymbolOccurrence(
                sym, node, sym.getSimpleName(), Role.REFERENCE, CompilerRange.FROM_START_TO_END);
          }
        }
      }
    }
  }

  private void resolveMemberReferenceTree(MemberReferenceTree node, TreePath treePath) {
    Element sym = trees.getElement(treePath);
    if (sym != null) {
      emitSymbolOccurrence(
          sym, node, sym.getSimpleName(), Role.REFERENCE, CompilerRange.FROM_END_TO_SYMBOL_NAME);
    }
  }

  private void resolveMemberSelectTree(MemberSelectTree node, TreePath treePath) {
    Element sym = trees.getElement(treePath);
    if (sym != null) {
      emitSymbolOccurrence(
          sym, node, sym.getSimpleName(), Role.REFERENCE, CompilerRange.FROM_END_TO_SYMBOL_NAME);
    }
  }

  private void resolveNewClassTree(NewClassTree node, TreePath treePath) {
    // ignore anonymous classes - otherwise there will be a local reference to
    // itself
    if (node.getIdentifier() != null && node.getClassBody() == null) {
      Element sym = trees.getElement(treePath);
      if (sym != null) {
        TreePath parentPath = treePath.getParentPath();
        Element parentSym = trees.getElement(parentPath);

        if (parentSym == null || parentSym.getKind() != ElementKind.ENUM_CONSTANT) {
          TreePath identifierTreePath = nodes.get(node.getIdentifier());
          Element identifierSym = trees.getElement(identifierTreePath);
          // Simplest case, e.g. `new String()`
          if (identifierSym != null) {
            emitSymbolOccurrence(
                sym,
                node,
                identifierSym.getSimpleName(),
                Role.REFERENCE,
                CompilerRange.FROM_TEXT_SEARCH);
          }
          // More complex case, where the type is annotated: `new @TypeParameters
          // String()`
          else if (node.getIdentifier().getKind() == Tree.Kind.ANNOTATED_TYPE) {
            AnnotatedTypeTree annotatedTypeTree = (AnnotatedTypeTree) node.getIdentifier();
            if (annotatedTypeTree.getUnderlyingType() != null
                && annotatedTypeTree.getUnderlyingType().getKind() == Tree.Kind.IDENTIFIER) {
              IdentifierTree ident = (IdentifierTree) annotatedTypeTree.getUnderlyingType();
              emitSymbolOccurrence(
                  sym, ident, ident.getName(), Role.REFERENCE, CompilerRange.FROM_TEXT_SEARCH);
            }
          }
        }
      }
    }
  }

  // =================================================
  // Utilities to generate SemanticDB data structures.
  // =================================================

  private Semanticdb.Signature semanticdbSignature(Element sym) {

    return new SemanticdbSignatures(globals, locals, types).generateSignature(sym);
  }

  private String semanticdbSymbol(Element sym) {
    return globals.semanticdbSymbol(sym, locals);
  }

  private Optional<Semanticdb.Range> semanticdbRange(
      Tree tree, CompilerRange kind, Element sym, String name) {
    if (sym == null) return Optional.empty();

    int start = (int) this.sourcePositions.getStartPosition(compUnitTree, tree);
    int end = (int) this.sourcePositions.getEndPosition(compUnitTree, tree);
    if (kind.isPlusOne()) start++;

    if (name != null) {
      if (kind.isFromTextSearch() && name.length() > 0) {
        Optional<RangeFinder.StartEndRange> startEndRange =
            RangeFinder.findRange(sym, name, start, end, this.source, kind.isFromEnd());
        if (startEndRange.isPresent()) {
          start = startEndRange.get().start;
          end = startEndRange.get().end;
        }
      } else if (kind.isFromPoint()) {
        if (start != Diagnostic.NOPOS) {
          // text may not exist or may be out of bounds (e.g. generated source like
          // Lombok)
          int testEnd = start + name.length();
          if (source.length() > testEnd && source.substring(start, testEnd).equals(name))
            end = testEnd;
        }
      } else if (kind.isFromEndPoint()) {
        if (end != Diagnostic.NOPOS) {
          // text may not exist or may be out of bounds (e.g. generated source like
          // Lombok)
          int testStart = end - name.length();
          if (testStart >= 0
              && source.length() > end
              && source.substring(testStart, end).equals(name)) start = testStart;
        }
      }
    }

    if (start != Diagnostic.NOPOS && end != Diagnostic.NOPOS && end > start) {
      LineMap lineMap = compUnitTree.getLineMap();
      Semanticdb.Range range =
          Semanticdb.Range.newBuilder()
              .setStartLine((int) lineMap.getLineNumber(start) - 1)
              .setStartCharacter((int) lineMap.getColumnNumber(start) - 1)
              .setEndLine((int) lineMap.getLineNumber(end) - 1)
              .setEndCharacter((int) lineMap.getColumnNumber(end) - 1)
              .build();

      range = correctForTabs(range, lineMap, start);

      return Optional.of(range);
    }
    return Optional.empty();
  }

  private Semanticdb.Range correctForTabs(Semanticdb.Range range, LineMap lineMap, int start) {
    int startLinePos = (int) lineMap.getPosition(lineMap.getLineNumber(start), 0);

    // javac replaces every tab with 8 spaces in the linemap. As this is potentially
    // inconsistent
    // with the source file itself, we adjust for that here if the line is actually
    // indented with
    // tabs.
    // As for every tab there are 8 spaces, we remove 7 spaces for every tab to get
    // the correct
    // char offset (note: different to _column_ offset your editor shows)
    if (this.source.charAt(startLinePos) == '\t') {
      int count = 1;
      while (this.source.charAt(++startLinePos) == '\t') count++;
      range =
          range.toBuilder()
              .setStartCharacter(range.getStartCharacter() - (count * 7))
              .setEndCharacter(range.getEndCharacter() - (count * 7))
              .build();
    }

    return range;
  }

  private Optional<Semanticdb.SymbolOccurrence> semanticdbOccurrence(
      Element sym, Optional<Semanticdb.Range> range, Role role) {
    if (range.isPresent()) {
      String ssym = semanticdbSymbol(sym);
      if (!ssym.equals(SemanticdbSymbols.NONE)) {
        Semanticdb.SymbolOccurrence occ = symbolOccurrence(ssym, range.get(), role);
        return Optional.of(occ);
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }
  }

  private String semanticdbText() {
    if (source != null) return source;
    try {
      source = compUnitTree.getSourceFile().getCharContent(true).toString();
    } catch (IOException e) {
      source = "";
    }
    return source;
  }

  private String semanticdbMd5() {
    try {
      return MD5.digest(compUnitTree.getSourceFile().getCharContent(true).toString());
    } catch (IOException | NoSuchAlgorithmException e) {
      return "";
    }
  }

  private int semanticdbSymbolInfoProperties(Element sym) {
    int properties = 0;
    properties |=
        sym.getKind() == ElementKind.ENUM || sym.getKind() == ElementKind.ENUM_CONSTANT
            ? Property.ENUM_VALUE
            : 0;
    for (Modifier modifier : sym.getModifiers()) {
      if (modifier == Modifier.STATIC) properties |= Property.STATIC_VALUE;
      else if (modifier == Modifier.DEFAULT) properties |= Property.DEFAULT_VALUE;
      else if (modifier == Modifier.FINAL) properties |= Property.FINAL_VALUE;
      else if (modifier == Modifier.ABSTRACT) properties |= Property.ABSTRACT_VALUE;
    }
    // for default interface methods, Modifier.ABSTRACT is also set...
    if (((properties & Property.ABSTRACT_VALUE) > 0) && ((properties & Property.DEFAULT_VALUE) > 0))
      properties ^= Property.ABSTRACT_VALUE;
    return properties;
  }

  private List<String> semanticdbParentSymbols(TypeElement typeElement) {
    ArrayList<String> parentSymbols = new ArrayList<>();
    Set<TypeElement> parentElements = semanticdbParentTypeElements(typeElement, new HashSet<>());
    for (TypeElement parentElement : parentElements) {
      String ssym = semanticdbSymbol(parentElement);
      if (!Objects.equals(ssym, SemanticdbSymbols.NONE)) {
        parentSymbols.add(ssym);
      }
    }
    return parentSymbols;
  }

  private Set<TypeElement> semanticdbParentTypeElements(
      TypeElement typeElement, Set<TypeElement> result) {
    TypeMirror superType = typeElement.getSuperclass();
    semanticdbParentSymbol(superType, result);
    for (TypeMirror interfaceType : typeElement.getInterfaces()) {
      semanticdbParentSymbol(interfaceType, result);
    }

    return result;
  }

  private void semanticdbParentSymbol(TypeMirror elementType, Set<TypeElement> result) {
    if (!(elementType instanceof NoType)) {
      Element superElement = types.asElement(elementType);
      if (superElement != null && superElement instanceof TypeElement) {
        result.add((TypeElement) superElement);
        semanticdbParentTypeElements((TypeElement) superElement, result);
      }
    }
  }

  private Set<String> semanticdbOverrides(
      ExecutableElement sym, Element enclosingElement, HashSet<String> overriddenSymbols) {
    if (enclosingElement instanceof TypeElement) {
      List<? extends TypeMirror> superTypes = types.directSupertypes(enclosingElement.asType());
      // iterate through all super types
      for (TypeMirror superType : superTypes) {
        if (superType instanceof DeclaredType) {
          Element superElement = ((DeclaredType) superType).asElement();
          // find all elements of super class
          if (superElement instanceof TypeElement) {
            boolean methodFound = false;
            List<? extends Element> enclosedElements =
                ((TypeElement) superElement).getEnclosedElements();
            for (Element enclosedElement : enclosedElements) {
              // check the element is a method
              if (enclosedElement instanceof ExecutableElement) {
                ExecutableElement enclosedExecutableElement = (ExecutableElement) enclosedElement;
                // check the method overrides the original method
                if (elements.overrides(
                    sym, enclosedExecutableElement, (TypeElement) sym.getEnclosingElement())) {
                  String symbol = semanticdbSymbol(enclosedExecutableElement);
                  overriddenSymbols.add(symbol);
                  methodFound = true;
                  semanticdbOverrides(enclosedExecutableElement, superElement, overriddenSymbols);
                }
              }
            }
            if (!methodFound) {
              semanticdbOverrides(sym, superElement, overriddenSymbols);
            }
          }
        }
      }
    }
    return overriddenSymbols;
  }

  private Semanticdb.Access semanticdbAccess(Element sym) {
    for (Modifier modifier : sym.getModifiers()) {
      if (modifier == Modifier.PRIVATE) return privateAccess();
      if (modifier == Modifier.PUBLIC) return publicAccess();
      if (modifier == Modifier.PROTECTED) return protectedAccess();
    }
    return privateWithinAccess(semanticdbSymbol(sym.getEnclosingElement()));
  }

  private static String semanticdbUri(
      CompilationUnitTree compUnitTree, SemanticdbJavacOptions options) {
    Path absolutePath =
        SemanticdbTaskListener.absolutePathFromUri(options, compUnitTree.getSourceFile());
    Path uriPath =
        absolutePath.startsWith(options.sourceroot)
            ? options.sourceroot.relativize(absolutePath)
            : absolutePath;
    StringBuilder out = new StringBuilder();
    Iterator<Path> it = uriPath.iterator();
    if (it.hasNext()) out.append(it.next().getFileName().toString());
    while (it.hasNext()) {
      Path part = it.next();
      out.append('/').append(part.getFileName().toString());
    }
    return out.toString();
  }

  private Semanticdb.Documentation semanticdbDocumentation(Tree tree) {
    try {
      TreePath treePath = nodes.get(tree);
      String doc = trees.getDocComment(treePath);
      if (doc == null) return null;

      return Semanticdb.Documentation.newBuilder()
          .setFormat(Semanticdb.Documentation.Format.JAVADOC)
          .setMessage(doc)
          .build();
    } catch (NullPointerException e) {
      // Can happen in `getDocComment()`
      // Caused by: java.lang.NullPointerException
      // at com.sun.tools.javac.model.JavacElements.cast(JavacElements.java:605)
      // at
      // com.sun.tools.javac.model.JavacElements.getTreeAndTopLevel(JavacElements.java:543)
      // at
      // com.sun.tools.javac.model.JavacElements.getDocComment(JavacElements.java:321)
      // at
      // com.sourcegraph.semanticdb_javac.SemanticdbVisitor.semanticdbDocumentation(SemanticdbVisitor.java:233)
      return null;
    }
  }
}
