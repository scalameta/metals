package tests

import java.nio.file.Files

class RenameFilesLspSuite extends BaseRenameFilesLspSuite("rename_files") {
  private val prefix = "a/src/main/scala"

  /* Unchanged semantics */
  renamed(
    "identity",
    s"""|/$prefix/A/Sun.scala
        |package A
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/A/Sun.scala"),
    expectedRenames = Map.empty,
  )

  renamed(
    "no-package",
    s"""|/$prefix/A/Sun.scala
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/A/Sun.scala"),
    expectedRenames = Map.empty,
  )

  /* Single-package semantics */
  renamed(
    "single-package",
    s"""|/$prefix/A/Sun.scala
        |package <<A>>
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/A/B/Sun.scala"),
    expectedRenames = Map("A" -> "A.B"),
  )

  /* We should not rename if moving outside the workspace */
  renamed(
    "outside-workspace",
    s"""|/$prefix/A/Sun.scala
        |package A
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(
      s"$prefix/A/Sun.scala" -> Files.createTempFile("Sun", ".scala").toString()
    ),
    expectedRenames = Map.empty,
  )

  renamed(
    "single-package-non-matching-structure",
    s"""|/$prefix/A/B/Sun.scala
        |package A
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/C/Sun.scala"),
    expectedRenames = Map(),
  )

  /* Multi-package-semantics */

  // Changes in all package parts mean that a file was moved to a completely
  // different scope and we can merge packages into one
  renamed(
    "multi-package-rename-all",
    s"""|/$prefix/A/B/Sun.scala
        |<<package A; package B>>
        |object Sun
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/C/D/Sun.scala"),
    expectedRenames = Map("package A; package B" -> "package C.D"),
  )

  renamed(
    "multi-package-rename-last",
    s"""|/$prefix/A/B/Sun.scala
        |package A
        |package <<B>>
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/C/Sun.scala"),
    expectedRenames = Map("B" -> "C"),
  )

  renamed(
    "multi-package-rename-last-brackets",
    s"""|/$prefix/A/B/Sun.scala
        |package A {
        | package <<B>> {
        |   object Sun
        | }
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/C/Sun.scala"),
    expectedRenames = Map("B" -> "C"),
  )

  renamed(
    "multi-package-rename-first-head",
    s"""|/$prefix/A/B/C/D/Sun.scala
        |package <<A>>.B
        |package C.D
        |object Sun
        |""".stripMargin,
    fileRenames =
      Map(s"$prefix/A/B/C/D/Sun.scala" -> s"$prefix/E/B/C/D/Sun.scala"),
    expectedRenames = Map("A" -> "E"),
  )

  renamed(
    "multi-package-rename-first-end",
    s"""|/$prefix/A/B/C/D/Sun.scala
        |package <<A.B
        |package C.D>>
        |object Sun
        |""".stripMargin,
    fileRenames =
      Map(s"$prefix/A/B/C/D/Sun.scala" -> s"$prefix/A/E/C/D/Sun.scala"),
    expectedRenames = Map("A.B\npackage C.D" -> "A.E.C.D"),
  )

  renamed(
    "outer-matches-path",
    s"""|/$prefix/A/Sun.scala
        |package <<A>>
        |package object C {
        | object Sun
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/A/M/Sun.scala"),
    expectedRenames = Map("A" -> "A.M"),
  )

  renamed(
    "multi-package-rename-to-one",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A
        |package B>>
        |
        |object Sun
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/C/Sun.scala"),
    expectedRenames = Map("A\npackage B" -> "C"),
  )

  renamed(
    "multi-package-add-between",
    s"""|/$prefix/A/B/Sun.scala
        |package A
        |package <<B>>
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/C/B/Sun.scala"),
    expectedRenames = Map("B" -> "C.B"),
  )

  renamed(
    "multi-package-add-to-last",
    s"""|/$prefix/A/B/Sun.scala
        |package A
        |package <<B>>
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/B/C/Sun.scala"),
    expectedRenames = Map("B" -> "B.C"),
  )

  renamed(
    "multi-package-dir-to-more-nested",
    s"""|/$prefix/A/Sun.scala
        |package <<A>>
        |object Sun
        |/$prefix/A/Moon.scala
        |package <<A>>
        |object Sun
        |""".stripMargin,
    fileRenames = Map(
      s"$prefix/A" -> s"$prefix/A/B"
    ),
    expectedRenames = Map("A" -> "A.B"),
  )

  renamed(
    "multi-package-dir-to-less-nested",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A.B>>
        |object Sun 
        |/$prefix/A/B/Moon.scala
        |package <<A.B>>
        |object Moon 
        |""".stripMargin,
    fileRenames = Map(
      s"$prefix/A/B" -> s"$prefix/A"
    ),
    expectedRenames = Map("A.B" -> "A"),
  )

  renamed(
    "multi-package-dir-to-another-file-tree",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A.B>>
        |object Sun
        |/$prefix/A/B/Moon.scala
        |package <<A.B>>
        |object Moon
        |""".stripMargin,
    fileRenames = Map(
      s"$prefix/A/B" -> s"$prefix/C/D/E"
    ),
    expectedRenames = Map("A.B" -> "C.D.E"),
  )

  /* Package object semantics */

  renamed(
    "package-object",
    s"""|/$prefix/A/B/packageFile.scala
        |<<package A>>
        |package object <<B>> 
        |""".stripMargin,
    fileRenames = Map(
      s"$prefix/A/B" -> s"$prefix/C/D"
    ),
    expectedRenames = Map("package A" -> "package C", "B" -> "D"),
  )

  /* References semantics */

  renamed(
    "references-simple-file-move",
    s"""|/$prefix/A/Sun.scala
        |package <<A>>
        |object Sun 
        |/$prefix/B/Moon.scala
        |package B
        |import <<A>>.Sun
        |object Moon 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/C/Sun.scala"),
    expectedRenames = Map("A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "references-wildcard-imports",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun 
        |/$prefix/B/Moon.scala
        |package B
        |import <<A>>.B._
        |import <<A>>.B.{Sun, _}
        |object Moon 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/C/B"),
    expectedRenames = Map("A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "references-package-object",
    s"""|/$prefix/A/B/package.scala
        |<<package A>>
        |package object <<B>> {
        | trait Sun
        |}
        |/$prefix/X/Moon.scala
        |package X
        |import <<A>>.<<B>>.Sun
        |object Moon 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/C/D"),
    expectedRenames = Map("package A" -> "package C", "B" -> "D", "A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "scala3-pkg",
    s"""|/$prefix/A/B/package.scala
        |<<package A:
        |  package B:>>
        |    object M
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/C"),
    expectedRenames = Map("package A:\n  package B:" -> "  package C:"),
    sourcesAreCompiled = true,
    scalaVersion = Some("3.2.0"),
  )

  renamed(
    "references-same-package-no-imports",
    s"""|/$prefix/A/Mars.scala
        |package <<A>>
        |object Phobos 
        |object Deimos
        |/$prefix/A/Earth.scala
        |package A
        |<<//>>
        |object Sun {
        |  println(Phobos)
        |  println(Deimos)
        |  println(Phobos)
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Mars.scala" -> s"$prefix/B/Mars.scala"),
    expectedRenames = Map("A" -> "B", "//" -> "import B.{Phobos, Deimos}\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "references-package-imports",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun { }
        |/$prefix/B/Moon.scala
        |package B
        |import <<A>>.{B => BB}
        |import <<A>>._ 
        |object Moon {
        |  println(BB.Sun)
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A" -> s"$prefix/C"),
    expectedRenames = Map("A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "references-dir-rename-no-new-imports",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun
        |/$prefix/A/B/Moon.scala
        |package <<A>>.B
        |object Moon {
        |  println(Sun)
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A" -> s"$prefix/C"),
    expectedRenames = Map("A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "references-multi-package-new-imports",
    s"""|/$prefix/A/B/Sun.scala
        |package A.<<B>>
        |object Sun
        |/$prefix/A/B/Moon.scala
        |package A
        |package B
        |import scala.util.Try
        |<<//>>
        |object Moon {
        |  println(Try(Sun))
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/B/C/Sun.scala"),
    expectedRenames = Map("B" -> "B.C", "//" -> "import A.B.C.Sun\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "references-mixed-import",
    s"""|/$prefix/A/Mars.scala
        |package <<A>>
        |object Phobos
        |object Deimos
        |/$prefix/A/Pluto.scala
        |package A
        |object Pluto
        |/$prefix/B/Solar.scala
        |package B
        |import A.{<<Phobos => P, >>Pluto<<, Deimos>>}
        |<<//>>
        |object Mars
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Mars.scala" -> s"$prefix/C/Mars.scala"),
    expectedRenames = Map(
      "A" -> "C",
      "Phobos => P, " -> "",
      ", Deimos" -> "",
      "//" -> "import C.{Phobos => P, Deimos}\n//",
    ),
    sourcesAreCompiled = true,
  )

  renamed(
    "already-correct-pkg",
    s"""|/$prefix/A/Dep.scala
        |package A.inner
        |
        |class Dep
        |/$prefix/B/Usage.scala
        |package B
        |import A.inner.Dep
        |
        |class Usage(b : Dep)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Dep.scala" -> s"$prefix/A/inner/Dep.scala"),
    expectedRenames = Map(),
    sourcesAreCompiled = true,
  )

  /* Cases that are not yet supported */
  renamed(
    "unsupported-common-pkg-part".ignore,
    s"""|/$prefix/A/B/Dep.scala
        |package A
        |package <<B>> {
        | class Dep
        |}
        |/$prefix/B/Usage.scala
        |package A
        |import <<B>>.Dep
        |
        |class Usage(b : Dep)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Dep.scala" -> s"$prefix/A/C/Dep.scala"),
    expectedRenames = Map("B" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "unsupported-inner-pkg".ignore,
    s"""|/$prefix/A/Dep.scala
        |package <<A>> {
        |  class Foo
        |  package inner {
        |   class Dep
        |  }
        |}
        |/$prefix/B/Usage.scala
        |package B
        |import <<A>>.inner.Dep
        |
        |class Usage(b : Dep)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Dep.scala" -> s"$prefix/C/Dep.scala"),
    expectedRenames = Map("A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "static-obj".ignore,
    s"""|/$prefix/A/Dep.scala
        |package <<A>>
        |object Obj {
        | case class Dep()
        |}
        |/$prefix/B/Usage.scala
        |package B
        |import <<A>>.Obj.Dep
        |
        |class Usage(b : Dep)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Dep.scala" -> s"$prefix/M/Dep.scala"),
    expectedRenames = Map("A" -> "M"),
    sourcesAreCompiled = true,
  )

  renamed(
    "unsupported-qualified-refs",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun 
        |/$prefix/X/Moon.scala
        |package X
        |<<//>>should not import anything
        |object Moon {
        |  A.B.Sun // should be refactored
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A" -> s"$prefix/C"),
    expectedRenames = Map("A" -> "C", "//" -> "import C.B.Sun\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "unsupported-package-renames",
    s"""|/$prefix/A/B/Sun.scala
        |package A.<<B>>
        |object Sun 
        |/$prefix/X/Moon.scala
        |package X
        |import A.{B => BB}
        |<<//>>should not import anything
        |object Moon {
        |  println(BB.Sun)
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/A/C"),
    expectedRenames = Map("B" -> "C", "//" -> "import A.C.Sun\n//"),
    sourcesAreCompiled = true,
  )
}
