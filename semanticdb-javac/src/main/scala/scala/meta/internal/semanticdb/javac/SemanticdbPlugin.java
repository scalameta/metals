package scala.meta.internal.semanticdb.javac;

import com.sun.source.util.Plugin;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

/** Entrypoint of the semanticdb-javac compiler plugin. */
public class SemanticdbPlugin implements Plugin {

	public static String stubClassName = "META-INF-semanticdb-stub";

	@Override
	public String getName() {
		return "MetalsSemanticdb";
	}

	@Override
	public void init(JavacTask task, String... args) {
		Trees trees = Trees.instance(task);
		SemanticdbReporter reporter = new SemanticdbReporter(trees);
		SemanticdbJavacOptions options = SemanticdbJavacOptions.parse(args, task);
		GlobalSymbolsCache globals = new GlobalSymbolsCache(options);
		task.addTaskListener(new SemanticdbTaskListener(options, task, trees, globals, reporter));
	}
}
