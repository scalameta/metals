package scala.meta.internal.semanticdb.javac;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class SemanticdbOptionBuilder {
	private String previousArg = "";
	private final ArrayList<String> oldArgs = new ArrayList<>();
	private final ArrayList<String> result = new ArrayList<>();
	private boolean isClasspathUpdated = false;

	public static final String ERRORPATH = System.getProperty("semanticdb.errorpath", "");
	private static final String PLUGINPATH = System.getProperty("semanticdb.pluginpath", "");
	private static final String SOURCEROOT = System.getProperty("semanticdb.sourceroot", "");
	private static final String TARGETROOT = System.getProperty("semanticdb.targetroot", "");
	private static final String OUTPUT = System.getProperty("semanticdb.output", "");
	private static final String OLD_OUTPUT = System.getProperty("semanticdb.old-output", "");

	public void processArgument(String arg) {
		oldArgs.add(arg);
		arg = unwrapQuote(arg);
		if ("-processorpath".equals(previousArg) || "-classpath".equals(previousArg) || "-cp".equals(previousArg)) {
			isClasspathUpdated = true;
			result.add(PLUGINPATH + File.pathSeparator + arg);
		} else if (arg.startsWith("-J")) {
			// Ignore Java launcher arguments.
		} else if (arg.startsWith("-Xplugin:ErrorProne")) {
			// Disable ErrorProne since it's not necessary.
		} else if (arg.startsWith("-Xlint")) {
			// Disable linting options since they may fail the build.
		} else {
			result.add(arg);
		}

		previousArg = arg;
	}

	private String unwrapQuote(String arg) {
		if (arg.startsWith("\"") && arg.endsWith("\"")) {
			return arg.substring(1, arg.length() - 1);
		} else {
			return arg;
		}
	}

	private String wrapQuote(String arg) {
		if (arg.startsWith("\"") && arg.endsWith("\"")) {
			return arg;
		} else {
			return "\"" + arg + "\"";
		}
	}

	private String xpluginOption() {
		return String.format("\"-Xplugin:semanticdb -sourceroot:%s -targetroot:%s\"", SOURCEROOT, TARGETROOT);
	}

	public ArrayList<String> finalResult() {
		if (!isClasspathUpdated) {
			result.add("-classpath");
			result.add(PLUGINPATH);
		}
		result.add(xpluginOption());
		ArrayList<String> finalResult = new ArrayList<>();
		for (String arg : result) {
			finalResult.add(wrapQuote(arg));
		}
		return finalResult;
	}

	public void writeFile(String file, List<String> lines, OpenOption... options) throws IOException {
		Path path = Paths.get(file);
		Files.createDirectories(path.getParent());
		Files.write(path, lines, StandardCharsets.UTF_8, options);
	}

	public void write() throws IOException {
		writeFile(OUTPUT, finalResult());
		writeFile(OLD_OUTPUT, oldArgs, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}
}
