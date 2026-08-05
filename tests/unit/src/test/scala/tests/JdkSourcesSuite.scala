package tests

import scala.meta.dialects
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.io.AbsolutePath

class JdkSourcesSuite extends BaseSuite {

  private val withoutUserJavaHome =
    JdkSources.defaultJavaHome(userJavaHome = None)

  /**
   * The java home parsed from one spelling, or `None` if parsing failed.
   *
   * `defaultJavaHome` always adds the `JAVA_HOME` and `java.home` fallbacks
   * after it, so a rejected spelling leaves just the fallbacks behind.
   */
  private def readJavaHome(javaHomeDirectorySpelling: String): Option[String] =
    JdkSources.defaultJavaHome(Some(javaHomeDirectorySpelling)) match {
      case javaHome :: fallbacks if fallbacks == withoutUserJavaHome =>
        Some(javaHome.toString())
      case _ => None
    }

  private def checkJavaHome(
      name: String,
      expected: String,
      javaHomeDirectorySpellings: List[String],
  ): Unit =
    test(name) {
      for (javaHomeDirectorySpelling <- javaHomeDirectorySpellings)
        assertEquals(
          obtained = readJavaHome(javaHomeDirectorySpelling),
          expected = Some(expected),
          clue = s"read from: $javaHomeDirectorySpelling",
        )
    }

  /** Checks that none of the spellings parses into a java home. */
  private def checkNoJavaHome(
      name: String,
      javaHomeDirectorySpellings: List[String],
  ): Unit =
    test(name) {
      for (javaHomeDirectorySpelling <- javaHomeDirectorySpellings)
        assertEquals(
          obtained = readJavaHome(javaHomeDirectorySpelling),
          expected = None,
          clue = s"read from: $javaHomeDirectorySpelling",
        )
    }

  checkNoJavaHome(
    name = "empty-java-home-falls-back",
    javaHomeDirectorySpellings = List(""),
  )

  // A space is not allowed in a URI, so `URI.create` rejects both spellings on
  // every platform and Metals falls back to `JAVA_HOME`.
  checkNoJavaHome(
    name = "java-home-uri-holding-an-unencoded-space",
    javaHomeDirectorySpellings = List(
      "file:///opt/java home/jdk-17",
      "file:///C:/Program Files/Java/jdk-17",
    ),
  )

  if (isWindows) {
    checkJavaHome(
      name = "windows-java-home",
      expected = """C:\Program Files\Java\jdk-17""",
      javaHomeDirectorySpellings = List(
        """C:\Program Files\Java\jdk-17""",
        // The same directory with a trailing separator.
        """C:\Program Files\Java\jdk-17\""",
        "file:///C:/Program%20Files/Java/jdk-17",
        "file:///C:/Program%20Files/Java/jdk-17/",
        // A URI scheme is case-insensitive.
        "FILE:///C:/Program%20Files/Java/jdk-17",
      ),
    )

    // Windows reads a forward slash as a separator too.
    checkJavaHome(
      name = "windows-java-home-spelled-with-forward-slashes",
      expected = """C:\Program Files\Java\jdk-17""",
      javaHomeDirectorySpellings = List(
        "C:/Program Files/Java/jdk-17",
        "C:/Program Files/Java/jdk-17/",
      ),
    )

    // A universal naming convention path points at a share on another machine
    // instead of a local drive: `\\host\share\jdk-17`. Windows is the only
    // platform that builds one from a URI authority like `file://host/share`.
    checkJavaHome(
      name = "windows-universal-naming-convention-java-home",
      expected = """\\host\share\jdk-17""",
      javaHomeDirectorySpellings = List(
        """\\host\share\jdk-17""",
        """\\host\share\jdk-17\""",
        "file://host/share/jdk-17",
        "file://host/share/jdk-17/",
      ),
    )
  } else {
    checkJavaHome(
      name = "linux-java-home",
      expected = "/usr/lib/jvm/java-17-openjdk-amd64",
      javaHomeDirectorySpellings = List(
        "/usr/lib/jvm/java-17-openjdk-amd64",
        "/usr/lib/jvm/java-17-openjdk-amd64/",
        "file:///usr/lib/jvm/java-17-openjdk-amd64",
        "file:///usr/lib/jvm/java-17-openjdk-amd64/",
        // JDK 8's `Path.toUri` writes a single slash after `file:`.
        "file:/usr/lib/jvm/java-17-openjdk-amd64",
        // A URI scheme is case-insensitive.
        "FILE:///usr/lib/jvm/java-17-openjdk-amd64",
        "File:///usr/lib/jvm/java-17-openjdk-amd64",
      ),
    )

    checkJavaHome(
      name = "macos-java-home",
      expected = "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home",
      javaHomeDirectorySpellings = List(
        "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home",
        "/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/",
        "file:///Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home",
        "file:///Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/",
        "file:/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home",
      ),
    )

    checkJavaHome(
      name = "java-home-holding-a-space",
      expected = "/opt/java home/jdk-17",
      javaHomeDirectorySpellings = List(
        "/opt/java home/jdk-17",
        "/opt/java home/jdk-17/",
        "file:///opt/java%20home/jdk-17",
        "file:///opt/java%20home/jdk-17/",
      ),
    )

    // Outside Windows a backslash is part of a file name, so `java\home` is a
    // single directory and a URI has to encode it as `%5C`.
    checkJavaHome(
      name = "java-home-holding-a-backslash",
      expected = """/opt/java\home/jdk-17""",
      javaHomeDirectorySpellings = List(
        """/opt/java\home/jdk-17""",
        """/opt/java\home/jdk-17/""",
        "file:///opt/java%5Chome/jdk-17",
        "file:///opt/java%5Chome/jdk-17/",
      ),
    )

    // A backslash separates nothing here, so a Windows path is a single
    // relative file name that `AbsolutePath` resolves against the working
    // directory.
    val workingDirectory = AbsolutePath.workingDirectory
    checkJavaHome(
      name = "windows-java-home-holds-no-separator-here",
      expected = raw"""$workingDirectory/C:\Program Files\Java\jdk-17""",
      javaHomeDirectorySpellings = List("""C:\Program Files\Java\jdk-17"""),
    )
    checkJavaHome(
      name = "windows-share-java-home-holds-no-separator-here",
      expected = raw"""$workingDirectory/\\host\share\jdk-17""",
      javaHomeDirectorySpellings = List("""\\host\share\jdk-17"""),
    )

    // `file://host/jdk` points at `/jdk` on a machine called `host`, which only
    // Windows can express, so `URI.create` fails and Metals falls back.
    checkNoJavaHome(
      name = "java-home-no-path-can-hold",
      javaHomeDirectorySpellings = List("file://host/jdk"),
    )
  }

  test("src.zip") {
    JdkSources().right.get
  }

  test("index-src.zip") {
    val jdk = JdkSources().right.get
    val symbolIndex = OnDemandSymbolIndex.empty()

    symbolIndex.addSourceJar(jdk, dialects.Scala213)

    val pathsDef = symbolIndex.definition(Symbol("java/nio/file/Paths#"))
    assert(pathsDef.isDefined, "Cannot find java/nio/file/Paths#")

    val swingBoxDef =
      symbolIndex.definition(Symbol("javax/swing/Box."))
    assert(swingBoxDef.isDefined, "Cannot find javax/swing/Box.")

  }
}
