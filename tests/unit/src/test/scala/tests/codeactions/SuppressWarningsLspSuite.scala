package tests.codeactions

import scala.meta.internal.metals.JavaLintOptions
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.codeactions.SuppressWarnings

import munit.Location
import tests.MbtTestInitializer

class SuppressWarningsLspSuite
    extends BaseCodeActionLspSuite(
      "suppress-warnings",
      MbtTestInitializer,
      useMbtLayout = true,
    ) {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      presentationCompilerDiagnostics = true,
      javaLintOptions = JavaLintOptions.default,
    )

  override protected def toPath(
      fileName: String,
      isSource: Boolean = true,
  ): String =
    if (isSource) s"a/src/main/java/a/$fileName"
    else s"a/$fileName"

  checkSuppressWarnings(
    "rawtypes-method",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |<<  public List names() {
       |    return new ArrayList();
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.raw.class.use",
    "rawtypes",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings("rawtypes")
       |  public List names() {
       |    return new ArrayList();
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "deprecation-method",
    """|package a;
       |
       |public class Example {
       |<<  public void run() {
       |    DeprecatedApi.old();
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.has.been.deprecated",
    "deprecation",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("deprecation")
       |  public void run() {
       |    DeprecatedApi.old();
       |  }
       |}
       |""".stripMargin,
    extraLayout = """|/a/src/main/java/a/DeprecatedApi.java
                     |package a;
                     |
                     |class DeprecatedApi {
                     |  @Deprecated
                     |  static void old() {}
                     |}
                     |""".stripMargin,
  )

  checkSuppressWarnings(
    "serial-class",
    """|package a;
       |
       |import java.io.Serializable;
       |
       |public class <<Example>> implements Serializable {
       |}
       |""".stripMargin,
    "compiler.warn.missing.SVUID",
    "serial",
    """|package a;
       |
       |import java.io.Serializable;
       |
       |@SuppressWarnings("serial")
       |public class Example implements Serializable {
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "append-existing",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings("unchecked")
       |<<  public List names() {
       |    return new ArrayList();
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.raw.class.use",
    "rawtypes",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings({"unchecked", "rawtypes"})
       |  public List names() {
       |    return new ArrayList();
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "local-variable",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  public void run() {
       |<<    List list = new ArrayList<>();>>
       |  }
       |}
       |""".stripMargin,
    "compiler.warn.raw.class.use",
    "rawtypes",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  public void run() {
       |    @SuppressWarnings("rawtypes")
       |    List list = new ArrayList<>();
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "class-field",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |<<  private List names = new ArrayList<>();>>
       |}
       |""".stripMargin,
    "compiler.warn.raw.class.use",
    "rawtypes",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings("rawtypes")
       |  private List names = new ArrayList<>();
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "constructor",
    """|package a;
       |
       |public class Example {
       |<<  public Example() {
       |    DeprecatedApi.old();
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.has.been.deprecated",
    "deprecation",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("deprecation")
       |  public Example() {
       |    DeprecatedApi.old();
       |  }
       |}
       |""".stripMargin,
    extraLayout = """|/a/src/main/java/a/DeprecatedApi.java
                     |package a;
                     |
                     |class DeprecatedApi {
                     |  @Deprecated
                     |  static void old() {}
                     |}
                     |""".stripMargin,
  )

  checkSuppressWarnings(
    "append-existing-array",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings({"unchecked", "serial"})
       |<<  public List names() {
       |    return new ArrayList();
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.raw.class.use",
    "rawtypes",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings({"unchecked", "serial", "rawtypes"})
       |  public List names() {
       |    return new ArrayList();
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "multiple-annotations",
    """|package a;
       |
       |public class Example implements Runnable {
       |  @Override
       |<<  public void run() {
       |    DeprecatedApi.old();
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.has.been.deprecated",
    "deprecation",
    """|package a;
       |
       |public class Example implements Runnable {
       |  @Override
       |  @SuppressWarnings("deprecation")
       |  public void run() {
       |    DeprecatedApi.old();
       |  }
       |}
       |""".stripMargin,
    extraLayout = """|/a/src/main/java/a/DeprecatedApi.java
                     |package a;
                     |
                     |class DeprecatedApi {
                     |  @Deprecated
                     |  static void old() {}
                     |}
                     |""".stripMargin,
  )

  checkSuppressWarnings(
    "cast-method",
    """|package a;
       |
       |public class Example {
       |<<  public void run() {
       |    String value = (String) "value";
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.redundant.cast",
    "cast",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("cast")
       |  public void run() {
       |    String value = (String) "value";
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "dep-ann-class",
    """|package a;
       |
       |/**
       | * @deprecated use something else
       | */
       |<<public class Example>> {
       |}
       |""".stripMargin,
    "compiler.warn.missing.deprecated.annotation",
    "dep-ann",
    """|package a;
       |
       |/**
       | * @deprecated use something else
       | */
       |@SuppressWarnings("dep-ann")
       |public class Example {
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "divzero-method",
    """|package a;
       |
       |public class Example {
       |<<  public int run() {
       |    return 1 / 0;
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.div.zero",
    "divzero",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("divzero")
       |  public int run() {
       |    return 1 / 0;
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "empty-method",
    """|package a;
       |
       |public class Example {
       |<<  public void run(boolean ok) {
       |    if (ok);
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.empty.if",
    "empty",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("empty")
       |  public void run(boolean ok) {
       |    if (ok);
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "fallthrough-method",
    """|package a;
       |
       |public class Example {
       |<<  public void run(int value) {
       |    switch (value) {
       |      case 0:
       |        value++;
       |      case 1:
       |        value++;
       |      default:
       |        value++;
       |    }
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.possible.fall-through.into.case",
    "fallthrough",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("fallthrough")
       |  public void run(int value) {
       |    switch (value) {
       |      case 0:
       |        value++;
       |      case 1:
       |        value++;
       |      default:
       |        value++;
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "finally-method",
    """|package a;
       |
       |public class Example {
       |<<  public int run() {
       |    try {
       |      return 1;
       |    } finally {
       |      return 2;
       |    }
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.finally.cannot.complete",
    "finally",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("finally")
       |  public int run() {
       |    try {
       |      return 1;
       |    } finally {
       |      return 2;
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "lossy-conversions-method",
    """|package a;
       |
       |public class Example {
       |<<  public void run() {
       |    short value = 0;
       |    value += 100000;
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.possible.loss.of.precision",
    "lossy-conversions",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("lossy-conversions")
       |  public void run() {
       |    short value = 0;
       |    value += 100000;
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "overloads-method",
    """|package a;
       |
       |import java.util.function.Consumer;
       |import java.util.function.Function;
       |
       |public class Example {
       |  public void run(Consumer<String> consumer) {
       |  }
       |
       |<<  public void run(Function<String, String> function) {
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.potentially.ambiguous.overload",
    "overloads",
    """|package a;
       |
       |import java.util.function.Consumer;
       |import java.util.function.Function;
       |
       |public class Example {
       |  public void run(Consumer<String> consumer) {
       |  }
       |
       |  @SuppressWarnings("overloads")
       |  public void run(Function<String, String> function) {
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "overrides-class",
    """|package a;
       |
       |<<public class Example>> {
       |  @Override
       |  public boolean equals(Object other) {
       |    return other instanceof Example;
       |  }
       |}
       |""".stripMargin,
    "compiler.warn.override.equals.but.not.hashcode",
    "overrides",
    """|package a;
       |
       |@SuppressWarnings("overrides")
       |public class Example {
       |  @Override
       |  public boolean equals(Object other) {
       |    return other instanceof Example;
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "removal-method",
    """|package a;
       |
       |public class Example {
       |<<  public void run() {
       |    RemovedApi.old();
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.has.been.deprecated.for.removal",
    "removal",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("removal")
       |  public void run() {
       |    RemovedApi.old();
       |  }
       |}
       |""".stripMargin,
    extraLayout = """|/a/src/main/java/a/RemovedApi.java
                     |package a;
                     |
                     |class RemovedApi {
                     |  @Deprecated(forRemoval = true)
                     |  static void old() {}
                     |}
                     |""".stripMargin,
  )

  checkSuppressWarnings(
    "unchecked-method",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |<<  public void run() {
       |    List raw = new ArrayList<String>();
       |    List<String> strings = raw;
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.prob.found.req",
    "unchecked",
    """|package a;
       |
       |import java.util.ArrayList;
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings("unchecked")
       |  public void run() {
       |    List raw = new ArrayList<String>();
       |    List<String> strings = raw;
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "static-method",
    """|package a;
       |
       |public class Example {
       |  static int count;
       |
       |<<  public void run(Example other) {
       |    other.count++;
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.static.not.qualified.by.type",
    "static",
    """|package a;
       |
       |public class Example {
       |  static int count;
       |
       |  @SuppressWarnings("static")
       |  public void run(Example other) {
       |    other.count++;
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "strictfp-method",
    """|package a;
       |
       |public class Example {
       |<<  public strictfp void run() {
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.strictfp",
    "strictfp",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("strictfp")
       |  public strictfp void run() {
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "synchronization-method",
    """|package a;
       |
       |public class Example {
       |<<  public void run() {
       |    synchronized (Integer.valueOf(1)) {
       |    }
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.attempt.to.synchronize.on.instance.of.value.based.class",
    "synchronization",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("synchronization")
       |  public void run() {
       |    synchronized (Integer.valueOf(1)) {
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "text-blocks-method",
    s"""|package a;
        |
        |public class Example {
        |<<  public void run() {
        |    String text = ""\"
        |        trailing${"   "}
        |        ""\";
        |  }>>
        |}
        |""".stripMargin,
    "compiler.warn.trailing.white.space.will.be.removed",
    "text-blocks",
    s"""|package a;
        |
        |public class Example {
        |  @SuppressWarnings("text-blocks")
        |  public void run() {
        |    String text = ""\"
        |        trailing${"   "}
        |        ""\";
        |  }
        |}
        |""".stripMargin,
  )

  checkSuppressWarnings(
    "this-escape-constructor",
    """|package a;
       |
       |public class Example {
       |<<  public Example() {
       |    overridable();
       |  }>>
       |
       |  public void overridable() {}
       |}
       |""".stripMargin,
    "compiler.warn.possible.this.escape",
    "this-escape",
    """|package a;
       |
       |public class Example {
       |  @SuppressWarnings("this-escape")
       |  public Example() {
       |    overridable();
       |  }
       |
       |  public void overridable() {}
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "try-method",
    """|package a;
       |
       |import java.io.Closeable;
       |import java.io.IOException;
       |
       |public class Example {
       |<<  public void run() throws IOException {
       |    try (Closeable closeable = null) {
       |    }
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.try.resource.not.referenced",
    "try",
    """|package a;
       |
       |import java.io.Closeable;
       |import java.io.IOException;
       |
       |public class Example {
       |  @SuppressWarnings("try")
       |  public void run() throws IOException {
       |    try (Closeable closeable = null) {
       |    }
       |  }
       |}
       |""".stripMargin,
  )

  checkSuppressWarnings(
    "varargs-method",
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |<<  public void run(List<String>... lists) {
       |  }>>
       |}
       |""".stripMargin,
    "compiler.warn.unchecked.varargs.non.reifiable.type",
    "varargs",
    """|package a;
       |
       |import java.util.List;
       |
       |public class Example {
       |  @SuppressWarnings("varargs")
       |  public void run(List<String>... lists) {
       |  }
       |}
       |""".stripMargin,
  )

  private def checkSuppressWarnings(
      name: String,
      original: String,
      diagnosticCode: String,
      warningName: String,
      expected: String,
      extraLayout: String = "",
  )(implicit loc: Location): Unit =
    test(name) {
      val fileName = "Example.java"
      val path = toPath(fileName)
      val code = original.replace("<<", "").replace(">>", "")

      cleanWorkspace()
      for {
        _ <- initialize(
          s"""|/.metals/mbt.json
              |{
              |  "namespaces": {
              |    "a": {
              |      "sources": ["a/src/main/java/**", "a/src/main/scala/**"]
              |    }
              |  }
              |}
              |/$path
              |$code
              |$extraLayout""".stripMargin
        )
        _ <- server.didOpen(path)
        diagnosticsPublished =
          server.awaitNextDiagnostics(
            path,
            _.exists(diagnostic =>
              Option(diagnostic.getCode()).exists(code =>
                code.isLeft() && code.getLeft() == diagnosticCode
              )
            ),
          )
        _ <- server.didFocus(path)
        _ <- diagnosticsPublished
        codeActions <- server.assertCodeAction(
          path,
          original,
          s"""|${SuppressWarnings.title(warningName)}
              |""".stripMargin,
          kind = Nil,
          filterAction = _.getTitle() == SuppressWarnings.title(warningName),
        )
        _ <- client.applyCodeAction(0, codeActions, server)
        _ <- server.didChange(path) { _ =>
          server.bufferContents(path)
        }
        _ <- server.didSave(path)
        _ = assertNoDiff(server.bufferContents(path), expected)
      } yield ()
    }
}
