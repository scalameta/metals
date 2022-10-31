package tests

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

  renamed(
    "single-package-non-matching-structure",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/C/Sun.scala"),
    expectedRenames = Map("A" -> "A.C"),
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
    "multi-package-rename-first-end",
    s"""|/$prefix/A/B/C/D/Sun.scala
        |package A.<<B>>
        |package C.D
        |object Sun 
        |""".stripMargin,
    fileRenames =
      Map(s"$prefix/A/B/C/D/Sun.scala" -> s"$prefix/A/E/C/D/Sun.scala"),
    expectedRenames = Map("B" -> "E"),
  )

  renamed(
    "multi-package-add-between",
    s"""|/$prefix/A/B/Sun.scala
        |package A
        |<<package B>>
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/C/B/Sun.scala"),
    expectedRenames = Map("package B" -> "package C\npackage B"),
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
        |package <<A>>
        |package object <<B>> 
        |""".stripMargin,
    fileRenames = Map(
      s"$prefix/A/B" -> s"$prefix/C/D"
    ),
    expectedRenames = Map("A" -> "C", "B" -> "D"),
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
        |package <<A>>
        |package object <<B>> {
        | trait Sun
        |}
        |/$prefix/X/Moon.scala
        |package X
        |import <<A>>.<<B>>.Sun
        |object Moon 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/C/D"),
    expectedRenames = Map("A" -> "C", "B" -> "D"),
    sourcesAreCompiled = true,
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
    expectedRenames = Map("A" -> "B", "//" -> "\nimport B.{Phobos, Deimos}//"),
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
    expectedRenames = Map("B" -> "B.C", "//" -> "\nimport A.B.C.Sun//"),
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
      "//" -> "import C.{Phobos => P, Deimos}//",
    ),
    sourcesAreCompiled = true,
  )

  /* Cases that are not yet supported */
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
    expectedRenames = Map("A" -> "C", "//" -> "\nimport C.B.Sun//"),
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
    expectedRenames = Map("B" -> "C", "//" -> "\nimport A.C.Sun//"),
    sourcesAreCompiled = true,
  )
}
