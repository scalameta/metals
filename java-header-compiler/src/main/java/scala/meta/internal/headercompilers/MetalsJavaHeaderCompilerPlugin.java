package scala.meta.internal.headercompilers;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.Trees;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.JavacTask;
import com.sun.source.doctree.DocTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import java.util.Arrays;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
import javax.tools.JavaCompiler;

/**
 * This is a Java compiler plugin that can be enabled with
 * -Xplugin:MetalsHeaderCompiler to do "header compilation" (aka. outline
 * compilation) where bodies of methods and fields are removed. This is useful
 * for two reasons:
 * 
 * <ul>
 * <li>Performance - No need to typecheck large bodies of code</li>
 * <li>Functionality - We don't need noisy diagnostics from files that the user
 * is not looking at in the IDE</li>
 * </ul>
 */
public class MetalsJavaHeaderCompilerPlugin implements Plugin {

	@Override
	public String getName() {
		return "MetalsHeaderCompiler";
	}

	@Override
	public void init(JavacTask task, String... args) {
		Trees trees = Trees.instance(task);
		var options = new Options();
		for (var arg : args) {
			if (arg.startsWith("-keep-bodies:")) {
				options.ignoreFiles.add(arg.substring("-keep-bodies:".length()));
			}
			if (arg.startsWith("-verbose")) {
				options.verbose = true;
			}
		}
		task.addTaskListener(new JavaTaskListener(trees, task, options));
	}

	private static class Options {
		private Set<String> ignoreFiles = new HashSet<>();
		private boolean verbose = false;

		public boolean isIgnored(String fileName) {
			if (ignoreFiles.contains(fileName)) {
				return true;
			}
			// Allow ignoring files by their suffix because it's tricky to get
			// it 100% correct with URIs inside jar files. This lets you do
			// `-keep-bodies:Foo.java` and it mostly works as you expect it to.
			for (var ignoreFile : ignoreFiles) {
				if (fileName.endsWith(ignoreFile)) {
					return true;
				}
			}
			return false;
		}
	}

	private static class JavaTaskListener implements TaskListener {
		private final Trees trees;
		private final JavacTask task;
		private final Options options;

		public JavaTaskListener(Trees trees, JavacTask task, Options options) {
			this.trees = trees;
			this.task = task;
			this.options = options;
		}

		@Override
		public void started(TaskEvent event) {
			if (event.getKind() != TaskEvent.Kind.ENTER) {
				return;
			}
			var fileName = event.getCompilationUnit().getSourceFile().getName();
			if (options.isIgnored(fileName)) {
				if (options.verbose) {
					System.out.println(String.format(
							"Header compiler: ignoring file %s because it matches an ignore pattern from -keep-bodies",
							fileName));
				}
				return;
			}

			// Get the compilation unit and transform it
			var compilationUnit = event.getCompilationUnit();
			// We can't easily get the context without casting to BasicJavacTask
			// For now, let's try accessing TreeMaker without it
			var context = ((BasicJavacTask) task).getContext();
			var translator = new BodyRemoverTranslator(context, trees);
			translator.translate((JCTree) compilationUnit);
		}

	}

	private static class BodyRemoverTranslator extends TreeTranslator {

		private final TreeMaker treeMaker;
		private final Trees trees;
		private int annotationTypeLevel = 0;
		private int classLevel = 0;
		private int executableLevel = 0;
		private final Map<Name, EnumInfo> enumInfoByName = new HashMap<>();

		public BodyRemoverTranslator(Context context, Trees trees) {
			this.treeMaker = TreeMaker.instance(context);
			this.trees = trees;
		}

		@Override
		public void visitClassDef(JCClassDecl classDecl) {
			boolean isAnnotation = (classDecl.mods.flags & Flags.ANNOTATION) != 0;
			Map<Name, EnumInfo> newlyRegisteredEnums = registerLocalEnums(classDecl);
			enumInfoByName.putAll(newlyRegisteredEnums);
			classLevel++;
			if (isAnnotation) {
				annotationTypeLevel++;
			}
			try {
				super.visitClassDef(classDecl);
			} finally {
				if (isAnnotation) {
					annotationTypeLevel--;
				}
				classLevel--;
				for (Map.Entry<Name, EnumInfo> entry : newlyRegisteredEnums.entrySet()) {
					Name enumName = entry.getKey();
					EnumInfo registeredInfo = entry.getValue();
					EnumInfo currentInfo = enumInfoByName.get(enumName);
					if (currentInfo == registeredInfo) {
						enumInfoByName.remove(enumName);
					}
				}
			}
		}

		@Override
		public void visitBlock(JCBlock block) {
			if ((block.flags & Flags.STATIC) != 0) {
				block.stats = List.nil();
			}
			executableLevel++;
			try {
				super.visitBlock(block);
			} finally {
				executableLevel--;
			}
		}

		@Override
		public void visitMethodDef(JCMethodDecl methodDecl) {

			boolean isConstructor = methodDecl.getName().toString().equals("<init>");
			if (isConstructor) {
				methodDecl.body = treeMaker.Block(0, List.nil());
				executableLevel++;
				try {
					super.visitMethodDef(methodDecl);
				} finally {
					executableLevel--;
				}
				return;
			}

			if (annotationTypeLevel > 0 && methodDecl.defaultValue != null) {
				TypeKind returnTypeKind = getTypeKindFromTree(methodDecl.getReturnType());
				JCExpression defaultValue = getAnnotationDefaultValue(methodDecl.getReturnType(), returnTypeKind);
				if (defaultValue != null) {
					methodDecl.defaultValue = defaultValue;
				} else {
					methodDecl.defaultValue = null;
				}
			}

			if (methodDecl.body == null) {
				executableLevel++;
				try {
					super.visitMethodDef(methodDecl);
				} finally {
					executableLevel--;
				}
				return;
			}

			Tree returnTypeTree = methodDecl.getReturnType();
			if (returnTypeTree == null) {
				super.visitMethodDef(methodDecl);
				return; // Should not happen for valid code
			}

			TypeKind returnTypeKind = getTypeKindFromTree(returnTypeTree);

			// Generate the default return value expression based on the type
			JCExpression defaultValue = getDefaultValue(methodDecl, returnTypeKind);

			List<JCStatement> newStatements;

			if (returnTypeKind == TypeKind.VOID) {
				// For void methods, create an empty list of statements.
				newStatements = List.nil();
			} else {
				// For other methods, create a 'return <defaultValue>;' statement.
				newStatements = List.of(treeMaker.Return(defaultValue));
			}

			// Create a new method body block with the new statements.
			methodDecl.body = treeMaker.Block(0, newStatements);

			executableLevel++;
			try {
				super.visitMethodDef(methodDecl);
			} finally {
				executableLevel--;
			}
		}

		@Override
		public void visitVarDef(JCVariableDecl varDecl) {

			boolean isParameter = (varDecl.mods.flags & Flags.PARAMETER) != 0;
			boolean isEnumConstant = (varDecl.mods.flags & Flags.ENUM) != 0;
			boolean isFieldContext = classLevel > 0 && executableLevel == 0;
			if (isFieldContext && !isParameter && !isEnumConstant) {
				TypeKind fieldTypeKind = getTypeKindFromTree(varDecl.getType());
				varDecl.init = getDefaultValue(varDecl.getType(), fieldTypeKind);
			}
			super.visitVarDef(varDecl);
		}

		private TypeKind getTypeKindFromTree(Tree typeTree) {
			// Check if the tree is a primitive type tree (int, boolean, etc.)
			if (typeTree instanceof PrimitiveTypeTree) {
				return ((PrimitiveTypeTree) typeTree).getPrimitiveTypeKind();
			}
			// Handle null typeTree case
			if (typeTree == null) {
				return TypeKind.DECLARED; // Default to object type for null trees
			}
			// The `void` keyword is its own special kind of tree node.
			// Tree.Kind is about syntax, TypeKind is about the type system.
			if (typeTree.getKind() == Tree.Kind.IDENTIFIER && "void".equals(typeTree.toString())) {
				return TypeKind.VOID;
			}
			// Any other kind of tree (Identifier, ParameterizedType, etc.) represents
			// a non-primitive type (an object, an array) which defaults to null.
			// We use DECLARED as a general-purpose stand-in for all of these.
			return TypeKind.DECLARED;
		}

		private JCExpression getDefaultValue(Tree tree, TypeKind kind) {
			switch (kind) {
			case BOOLEAN:
				return treeMaker.Literal(TypeTag.BOOLEAN, 0); // 0 represents false
			case BYTE:
				return treeMaker.Literal(TypeTag.BYTE, 0);
			case SHORT:
				return treeMaker.Literal(TypeTag.SHORT, 0);
			case INT:
				return treeMaker.Literal(TypeTag.INT, 0);
			case LONG:
				return treeMaker.Literal(TypeTag.LONG, 0L);
			case CHAR:
				return treeMaker.Literal(TypeTag.CHAR, 0);
			case FLOAT:
				return treeMaker.Literal(TypeTag.FLOAT, 0.0f);
			case DOUBLE:
				return treeMaker.Literal(TypeTag.DOUBLE, 0.0d);
			case VOID:
				return null; // Should be handled by the caller.
			// All non-primitive types (objects, arrays) default to null.
			case DECLARED:
			case ARRAY:
			case TYPEVAR:
				return treeMaker.Literal(TypeTag.BOT, null);
			case ERROR:
			default:
				throw new IllegalArgumentException("Unsupported type kind: " + kind + " for tree: " + tree);

			}
		}

		private JCExpression getAnnotationDefaultValue(Tree tree, TypeKind kind) {
			switch (kind) {
			case BOOLEAN:
				return treeMaker.Literal(TypeTag.BOOLEAN, 0);
			case BYTE:
				return treeMaker.Literal(TypeTag.BYTE, (byte) 0);
			case SHORT:
				return treeMaker.Literal(TypeTag.SHORT, (short) 0);
			case INT:
				return treeMaker.Literal(TypeTag.INT, 0);
			case LONG:
				return treeMaker.Literal(TypeTag.LONG, 0L);
			case CHAR:
				return treeMaker.Literal(TypeTag.CHAR, (char) 0);
			case FLOAT:
				return treeMaker.Literal(TypeTag.FLOAT, 0.0f);
			case DOUBLE:
				return treeMaker.Literal(TypeTag.DOUBLE, 0.0d);
			case DECLARED:
				if (tree == null) {
					return null;
				}
				if (isStringType(tree)) {
					return treeMaker.Literal("");
				}
				JCExpression enumDefault = getEnumDefaultValue(tree);
				if (enumDefault != null) {
					return enumDefault;
				}
				return null;
			case ARRAY:
			case TYPEVAR:
			case VOID:
			case ERROR:
			default:
				return null;
			}
		}

		private boolean isStringType(Tree tree) {
			String typeText = tree.toString();
			return "String".equals(typeText) || "java.lang.String".equals(typeText);
		}

		private JCExpression getEnumDefaultValue(Tree tree) {
			Name enumName = extractSimpleName(tree);
			if (enumName == null) {
				return null;
			}
			EnumInfo info = enumInfoByName.get(enumName);
			if (info == null || info.firstConstant == null) {
				return null;
			}
			JCExpression enumType = info.createTypeExpression(treeMaker);
			if (enumType == null) {
				if (tree instanceof JCExpression expression) {
					enumType = expression;
				} else {
					enumType = treeMaker.Ident(enumName);
				}
			}
			return treeMaker.Select(enumType, info.firstConstant);
		}

		private Name extractSimpleName(Tree tree) {
			if (tree instanceof JCIdent) {
				return ((JCIdent) tree).name;
			}
			if (tree instanceof JCFieldAccess) {
				return ((JCFieldAccess) tree).name;
			}
			return null;
		}

		private Map<Name, EnumInfo> registerLocalEnums(JCClassDecl classDecl) {
			Map<Name, EnumInfo> result = new HashMap<>();
			for (JCTree def : classDecl.defs) {
				if (def instanceof JCClassDecl nested && (nested.mods.flags & Flags.ENUM) != 0) {
					Name enumName = nested.name;
					Name firstConstant = findFirstEnumConstant(nested);
					if (firstConstant != null) {
						ClassSymbol symbol = nested.sym instanceof ClassSymbol ? (ClassSymbol) nested.sym : null;
						result.put(enumName, new EnumInfo(symbol, enumName, firstConstant));
					}
				}
			}
			return result;
		}

		private Name findFirstEnumConstant(JCClassDecl enumDecl) {
			for (JCTree def : enumDecl.defs) {
				if (def instanceof JCVariableDecl varDecl && (varDecl.mods.flags & Flags.ENUM) != 0) {
					return varDecl.name;
				}
			}
			return null;
		}

		private static final class EnumInfo {
			private final ClassSymbol symbol;
			private final Name simpleName;
			private final Name firstConstant;

			private EnumInfo(ClassSymbol symbol, Name simpleName, Name firstConstant) {
				this.symbol = symbol;
				this.simpleName = simpleName;
				this.firstConstant = firstConstant;
			}

			private JCExpression createTypeExpression(TreeMaker treeMaker) {
				if (symbol != null) {
					return treeMaker.QualIdent(symbol);
				}
				return treeMaker.Ident(simpleName);
			}
		}
	}
}