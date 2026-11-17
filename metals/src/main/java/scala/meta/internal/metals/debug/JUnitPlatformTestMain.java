package scala.meta.internal.metals.debug;

import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class JUnitPlatformTestMain {
  private static Path runnerClasspath;

  public static synchronized Path classpathEntry() throws Exception {
    if (runnerClasspath == null) {
      Path output = Files.createTempFile("metals-junit-platform-runner-", ".jar");
      output.toFile().deleteOnExit();
      String resource = JUnitPlatformTestMain.class.getName().replace('.', '/') + ".class";
      try (InputStream input = JUnitPlatformTestMain.class.getResourceAsStream("/" + resource);
          JarOutputStream archive = new JarOutputStream(Files.newOutputStream(output))) {
        if (input == null) {
          throw new IllegalStateException("Missing JUnit Platform runner class");
        }
        archive.putNextEntry(new JarEntry(resource));
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
          archive.write(buffer, 0, length);
        }
        archive.closeEntry();
      }
      runnerClasspath = output;
    }
    return runnerClasspath;
  }

  public static void main(String[] arguments) {
    int exitCode = 1;
    try {
      run(arguments);
      exitCode = 0;
    } catch (Throwable error) {
      error.printStackTrace(System.err);
    }
    System.exit(exitCode);
  }

  private static void run(String[] arguments) throws Exception {
    Class<?> selectorsType = Class.forName("org.junit.platform.engine.discovery.DiscoverySelectors");
    List<Object> selectors = new ArrayList<>();
    int index = 0;
    while (index < arguments.length) {
      Class<?> suite = Class.forName(arguments[index++], false, Thread.currentThread().getContextClassLoader());
      int count = Integer.parseInt(arguments[index++]);
      if (count == 0) {
        selectors.add(selectorsType.getMethod("selectClass", Class.class).invoke(null, suite));
      } else {
        for (int selected = 0; selected < count; selected++) {
          String name = arguments[index++];
          if (name.endsWith("()")) {
            name = name.substring(0, name.length() - 2);
          }
          Method method = selectMethod(suite, name);
          selectors.add(
              selectorsType
                  .getMethod("selectMethod", Class.class, Method.class)
                  .invoke(null, suite, method));
        }
      }
    }
    if (selectors.isEmpty()) {
      throw new IllegalArgumentException("No test suites were selected");
    }

    Class<?> builderType = Class.forName("org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder");
    Object builder = builderType.getMethod("request").invoke(null);
    builderType.getMethod("selectors", List.class).invoke(builder, selectors);
    Object request = builderType.getMethod("build").invoke(builder);
    Class<?> listenerType = Class.forName("org.junit.platform.launcher.TestExecutionListener");
    Class<?> summaryListenerType = Class.forName("org.junit.platform.launcher.listeners.SummaryGeneratingListener");
    Object listener = summaryListenerType.getConstructor().newInstance();
    Object listeners = Array.newInstance(listenerType, 1);
    Array.set(listeners, 0, listener);
    Object launcher = Class.forName("org.junit.platform.launcher.core.LauncherFactory")
        .getMethod("create").invoke(null);
    Class.forName("org.junit.platform.launcher.Launcher")
        .getMethod(
            "execute",
            Class.forName("org.junit.platform.launcher.LauncherDiscoveryRequest"),
            listeners.getClass())
        .invoke(launcher, request, listeners);

    Object summary = summaryListenerType.getMethod("getSummary").invoke(listener);
    Class<?> summaryType = Class.forName("org.junit.platform.launcher.listeners.TestExecutionSummary");
    PrintWriter output = new PrintWriter(System.out, true);
    summaryType.getMethod("printTo", PrintWriter.class).invoke(summary, output);
    summaryType.getMethod("printFailuresTo", PrintWriter.class).invoke(summary, output);
    output.flush();
    long found = (Long) summaryType.getMethod("getTestsFoundCount").invoke(summary);
    long failed = (Long) summaryType.getMethod("getTotalFailureCount").invoke(summary);
    if (found == 0 || failed != 0) {
      throw new AssertionError("Test execution failed: " + found + " tests found, " + failed + " failures");
    }
  }

  private static Method selectMethod(Class<?> suite, String name) {
    Map<String, Method> candidates = new LinkedHashMap<>();
    for (Method method : suite.getMethods()) {
      if (method.getName().equals(name) && !method.isBridge() && !method.isSynthetic()) {
        candidates.putIfAbsent(Arrays.toString(method.getParameterTypes()), method);
      }
    }
    for (Class<?> current = suite; current != null; current = current.getSuperclass()) {
      for (Method method : current.getDeclaredMethods()) {
        if (method.getName().equals(name) && !method.isBridge() && !method.isSynthetic()) {
          candidates.putIfAbsent(Arrays.toString(method.getParameterTypes()), method);
        }
      }
    }
    if (candidates.size() != 1) {
      throw new IllegalArgumentException(
          "Expected one test method " + suite.getName() + "#" + name + ", found " + candidates.size());
    }
    return candidates.values().iterator().next();
  }
}
