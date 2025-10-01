package tests

import java.nio.file.Files

import scala.meta.internal.metals.{BuildInfo => V}

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
    "basic-file-to-dir",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A.B>>
        |object Sun
        |/$prefix/C/D/Moon.scala
        |package C.D
        |object Moon
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/C/D"),
    expectedRenames = Map("A.B" -> "C.D"),
    sourcesAreCompiled = true,
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
        |<<import A.B._
        |import A.B.{Sun, _}>>
        |object Moon
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/C/B"),
    expectedRenames =
      Map("A" -> "C", "import A.B._\nimport A.B.{Sun, _}" -> "import C.B.{Sun, _}"),
    sourcesAreCompiled = true,
  )

  renamed(
    "two",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun
        |/$prefix/A/B/Saturn.scala
        |package <<A>>.B
        |object Saturn
        |/$prefix/B/Moon.scala
        |package B
        |import <<A>>.B.{Sun, Saturn}
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
    expectedRenames = Map("B" -> "D", "A" -> "C"),
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
    expectedRenames = Map("A" -> "B", "//" -> "import B.{Deimos, Phobos}\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "references-package-imports",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun { }
        |/$prefix/B/Moon.scala
        |package B
        |import <<A.B._>>
        |object Moon {
        |  println(Sun)
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A" -> s"$prefix/C"),
    expectedRenames = Map("A" -> "C", "A.B._" -> "C.B.Sun"),
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
    expectedRenames = Map("B" -> "B.C", "//" -> "import C.Sun\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "simple-wildcard-import",
    s"""|/$prefix/two/Foo.scala
        |package <<two>>
        |class Foo
        |class BB
        |/$prefix/one/One.scala
        |package one
        |
        |import <<two._>>
        |
        |class One(f: Foo)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/two/Foo.scala" -> s"$prefix/three/Foo.scala"),
    expectedRenames = Map("two" -> "three", "two._" -> "three.Foo"),
    sourcesAreCompiled = true,
  )

  renamed(
    "wildcard-imports-from-multiple-files",
    s"""|/$prefix/two/BB.scala
        |package two
        |class BB
        |
        |/$prefix/two/Foo.scala
        |package <<two>>
        |class Foo
        |
        |/$prefix/one/One.scala
        |package one
        |
        |import two._
        |<<//>>
        |class One(f: Foo)
        |class Two(f: BB)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/two/Foo.scala" -> s"$prefix/three/Foo.scala"),
    expectedRenames = Map("two" -> "three", "//" -> "import three.Foo\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "wildcard-and-rename-mult-files",
    s"""|/$prefix/two/Foo.scala
        |package <<two>>
        |class Foo
        |
        |/$prefix/two/BB.scala
        |package two
        |class BB
        |
        |/$prefix/one/One.scala
        |package one
        |
        |<<import two._
        |import two.{BB => CC}>>
        |
        |class One(f: Foo)
        |class Two(f: CC)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/two/Foo.scala" -> s"$prefix/three/Foo.scala"),
    expectedRenames = Map(
      "two" -> "three",
      "import two._\nimport two.{BB => CC}" -> "import two.{BB => CC}\nimport three.Foo",
    ),
    sourcesAreCompiled = true,
  )

  renamed(
    "wildcard-and-rename",
    s"""|/$prefix/two/Foo.scala
        |package <<two>>
        |class Foo
        |class BB
        |
        |/$prefix/one/One.scala
        |package one
        |
        |import <<two>>.{BB => CC<<,_>>}
        |<<//>>
        |
        |class One(f: Foo)
        |class Two(f: CC)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/two/Foo.scala" -> s"$prefix/three/Foo.scala"),
    expectedRenames =
      Map("two" -> "three", ",_" -> "", "//" -> "import three.Foo\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "wildcard-and-rename2",
    s"""|/$prefix/two/Foo.scala
        |package <<two>>
        |class Foo
        |class BB
        |
        |/$prefix/one/One.scala
        |package one
        |
        |import <<two._>>
        |import <<two.{BB => CC}>>
        |
        |class One(f: Foo)
        |class Two(f: CC)
        |""".stripMargin,
    fileRenames = Map(s"$prefix/two/Foo.scala" -> s"$prefix/three/Foo.scala"),
    expectedRenames = Map(
      "two" -> "three",
      "two._" -> "three.{BB => CC}",
      "two.{BB => CC}" -> "three.Foo",
    ),
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

  renamed(
    "unimport1",
    s"""|/$prefix/A/Sun.scala
        |package <<A>>
        |object Sun
        |object Moon 
        |/$prefix/B/Moon.scala
        |package B
        |import <<A.{Moon => _, _}>>
        |object Moon {
        | val sun = Sun
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/C/Moon.scala"),
    expectedRenames = Map("A" -> "C", "A.{Moon => _, _}" -> "C.{Moon => _, _}"),
    sourcesAreCompiled = true,
  )

  renamed(
    "unimport2",
    s"""|/$prefix/A/Sun.scala
        |package <<A>>
        |object Sun
        |object Moon 
        |/$prefix/B/Moon.scala
        |package B
        |import <<A._>>
        |<<import A.{Moon => _}>>
        |object Moon {
        | val sun = Sun
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/C/Moon.scala"),
    expectedRenames =
      Map("A" -> "C", "A._" -> "C._", "import A.{Moon => _}" -> "import C.{Moon => _}"),
    sourcesAreCompiled = true,
  )

  renamed(
    "common-pkg-part",
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
    "qualified-refs",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun 
        |/$prefix/X/Moon.scala
        |package X
        |
        |object Moon {
        |  <<A>>.B.Sun // should be refactored
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/C/B/Moon.scala"),
    expectedRenames = Map("A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "static-obj",
    s"""|/$prefix/A/Dep.scala
        |package <<A>>
        |object Obj {
        | case class Dep()
        | val END = 1
        |}
        |/$prefix/B/Usage.scala
        |package B
        |import <<A>>.Obj.Dep
        |import <<A>>.Obj
        |
        |class Usage(b : Dep) {
        | val m = <<A>>.Obj.Dep
        | val f = Obj.END
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Dep.scala" -> s"$prefix/M/Dep.scala"),
    expectedRenames = Map("A" -> "M"),
    sourcesAreCompiled = true,
  )

  renamed(
    "inner-pkg",
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
    "qualified-refs-dir-move",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>.B
        |object Sun 
        |/$prefix/X/Moon.scala
        |package X
        |
        |object Moon {
        |  <<A>>.B.Sun
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A" -> s"$prefix/C"),
    expectedRenames = Map("A" -> "C"),
    sourcesAreCompiled = true,
  )

  renamed(
    "partially-qualified-names",
    s"""|/$prefix/A/B/Sun.scala
        |package A.<<B>>
        |object Sun 
        |/$prefix/X/Moon.scala
        |package X
        |import A.B
        |<<//>>
        |object Moon {
        |  println(<<B.Sun>>)
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/A/C"),
    expectedRenames =
      Map("B" -> "C", "B.Sun" -> "Sun", "//" -> "import A.C.Sun\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "top-level-declarations",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>
        |package object <<B>> {
        | val m : Int = 3
        |}
        |/$prefix/X/Moon.scala
        |package X
        |import <<A>>.<<B>>.m
        |object Moon {
        | val o = m
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/C/D/Sun.scala"),
    expectedRenames = Map("A" -> "C", "B" -> "D"),
    sourcesAreCompiled = true,
  )

  renamed(
    "implicits-scala3",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A.B>>
        |
        |case class Sun()
        |case class Moon()
        |
        |given m : Moon = Moon()
        |
        |extension(sun: Sun)
        | def get2(using m : Moon) = 2
        |
        |/$prefix/X/Moon.scala
        |package X
        |import <<A.B.*
        |import A.B.given>>
        |object Moon:
        |  val o = Sun().get2
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/C/D/Sun.scala"),
    expectedRenames = Map(
      "A.B" -> "C.D",
      "A.B.*\nimport A.B.given" -> "C.D.given\nimport C.D.Sun",
    ),
    sourcesAreCompiled = true,
    scalaVersion = Some(V.scala3),
  )

  renamed(
    "wildcard-caseclass",
    s"""|/$prefix/A/Sun.scala
        |package <<A>>
        |case class Sun()
        |/$prefix/X/Moon.scala
        |package X
        |import <<A>>.<<_>>
        |object Moon {
        |  val o = Sun()
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"$prefix/C/Sun.scala"),
    expectedRenames = Map("A" -> "C", "_" -> "Sun"),
    sourcesAreCompiled = true,
  )

  renamed(
    "after-import-comment",
    s"""|/$prefix/A/B/Sun.scala
        |package A
        |package <<B>>
        |case class Sun()
        |/$prefix/A/D/Solaris.scala
        |package A
        |package D
        |case class Solaris()
        |/$prefix/A/B/Moon.scala
        |package A
        |package B
        |import D.Solaris //this is a comment
        |<<//>>
        |object Moon {
        | val sun = Sun()
        | val solaris = Solaris()
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/D/Sun.scala"),
    expectedRenames = Map("B" -> "D", "//" -> "import D.Sun\n//"),
    sourcesAreCompiled = true,
  )

  renamed(
    "import-new-given",
    s"""|/$prefix/A/B/Solaris.scala
        |package A
        |package <<B>>
        |case class Solaris()
        |given Solaris = Solaris()
        |/$prefix/A/B/Moon.scala
        |package A
        |package B
        |<<//>>
        |object Moon:
        | def calculate(using solaris: Solaris) = 2
        | val result = calculate
        |""".stripMargin,
    fileRenames =
      Map(s"$prefix/A/B/Solaris.scala" -> s"$prefix/A/D/Solaris.scala"),
    expectedRenames = Map("B" -> "D", "//" -> "import D.{Solaris, given}\n//"),
    sourcesAreCompiled = true,
    scalaVersion = Some(V.scala3),
  )

  renamed(
    "implicits",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A>>
        |package object <<B>> {
        | case class Sun()
        | case class Moon()
        | implicit val m : Moon = Moon()
        | implicit class XtentionSun(s : Sun) {
        |   def get2(implicit m : Moon) = 2
        | }
        |}
        |/$prefix/X/Moon.scala
        |package X
        |import <<A>>.<<B>>.<<_>>
        |object Moon {
        | val o = Sun().get2
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/C/D/Sun.scala"),
    expectedRenames =
      Map("A" -> "C", "B" -> "D", "_" -> "{Sun, XtentionSun, m}"),
    sourcesAreCompiled = true,
  )

  renamed(
    "move-to-the-same",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A.B>>
        |object Sun {
        | val isHot = true
        |}
        |/$prefix/A/Moon.scala
        |package A
        |<<import B.Sun
        |>>import <<B.Sun>>.isHot
        |
        |object Moon {
        |  val sun = Sun
        |  val isSunHot = Sun.isHot
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/A/Sun.scala"),
    expectedRenames =
      Map("A.B" -> "A", "import B.Sun\n" -> "", "B.Sun" -> "Sun"),
    sourcesAreCompiled = true,
  )

  renamed(
    "move-to-the-same2",
    s"""|/$prefix/A/B/Sun.scala
        |package <<A.B>>
        |object Sun {
        | val isHot = true
        |}
        |/$prefix/X/Moon.scala
        |package X
        |<<import A.B.Sun
        |>>
        |
        |object Moon {
        |  val sun = Sun
        |  val alsoSun = <<A.B.>>Sun
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B/Sun.scala" -> s"$prefix/X/Sun.scala"),
    expectedRenames = Map("A.B" -> "X", "import A.B.Sun\n" -> "", "A.B." -> ""),
    sourcesAreCompiled = true,
  )

  renamed(
    "do-not-make-changes-when-outside-of-root",
    s"""|/$prefix/A/Sun.scala
        |package A
        |object Sun 
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/Sun.scala" -> s"random/path/Sun.scala"),
    expectedRenames = Map.empty,
  )

  renamed(
    "infer-base-package",
    s"""|/$prefix/C/Sun.scala
        |package org.someorg.<<C>>
        |
        |class Sun
        |/$prefix/C/Moon.scala
        |package org.someorg.C
        |
        |import org.someorg.<<C>>.Sun
        |object Moon {
        | val o = new Sun()
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/C/Sun.scala" -> s"$prefix/C/D/Sun.scala"),
    expectedRenames = Map("C" -> "C.D"),
    sourcesAreCompiled = true,
  )

  renamed(
    "rename directory",
    s"""|/$prefix/Z/A/Sun.scala
        |package org.someorg.Z.<<A>>
        |
        |class Sun
        |/$prefix/B/Moon.scala
        |package org.someorg.B
        |
        |import org.someorg.Z.<<A._>>
        |object Moon {
        | val o = new Sun()
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/Z/A" -> s"$prefix/Z/C"),
    expectedRenames = Map("A" -> "C", "A._" -> "C.Sun"),
    sourcesAreCompiled = true,
  )

  renamed(
    "rename directory 2",
    s"""|/$prefix/Z/A/Sun.scala
        |package org.someorg.Z.<<A>>
        |
        |class Sun
        |/$prefix/Z/A/Mercury.scala
        |package org.someorg.Z.<<A>>
        |
        |class Mercury
        |/$prefix/B/Moon.scala
        |package org.someorg.B
        |
        |import org.someorg.Z.<<A._>>
        |object Moon {
        | val o = new Sun()
        | val o1 = new Mercury()
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/Z/A" -> s"$prefix/Z/C"),
    expectedRenames = Map("A" -> "C", "A._" -> "C.{Mercury, Sun}"),
    sourcesAreCompiled = true,
  )

  renamed(
    "rename directory 3",
    s"""|/$prefix/Z/A/Sun.scala
        |package org.someorg.Z.<<A>>
        |
        |class Sun
        |/$prefix/Z/A/Mercury.scala
        |package org.someorg.Z.<<A>>
        |
        |class Mercury
        |/$prefix/Z/A/D/Mars.scala
        |package org.someorg.Z.<<A>>.D
        |
        |class Mars
        |/$prefix/B/Moon.scala
        |package org.someorg.B
        |
        |import org.someorg.Z.<<A>>.D.Mars
        |import org.someorg.Z.<<A._>>
        |object Moon {
        | val o = new Sun()
        | val o1 = new Mercury()
        | val o2 = new Mars()
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/Z/A" -> s"$prefix/Z/C"),
    expectedRenames = Map("A" -> "C", "A._" -> "C.{Mercury, Sun}"),
    sourcesAreCompiled = true,
  )

  renamed(
    "wildcard-import-file-to-subdir",
    s"""|/$prefix/calcs/SomeCalc.scala
        |package research.gadgets.examples
        |package <<calcs>>
        |class SomeCalc {
        |  val a = 1
        |}
        |object Calc {
        |  val a = 1
        |}
        |/$prefix/calcs/VolCalc.scala
        |package research.gadgets.examples
        |package calcs
        |<<//>>
        |object VolCalc {
        |  val a = new SomeCalc()
        |  val b = Calc.a
        |}
        |/$prefix/scripts/Simulate.scala
        |package research.gadgets.examples
        |package scripts
        |import <<research.gadgets.examples.calcs._>>
        |object Simulation {
        |  val a = new SomeCalc()
        |  val b = Calc.a
        |}
        |""".stripMargin,
    fileRenames = Map(
      s"$prefix/calcs/SomeCalc.scala" -> s"$prefix/calcs/test/SomeCalc.scala"
    ),
    expectedRenames = Map(
      "calcs" -> "calcs.test",
      "research.gadgets.examples.calcs._" -> "research.gadgets.examples.calcs.test._",
      "//" -> "import research.gadgets.examples.calcs.test.{Calc, SomeCalc}\n//",
    ),
    sourcesAreCompiled = true,
  )

  /* Cases that are not yet supported */

  renamed(
    "unsupported-package-renames".ignore,
    s"""|/$prefix/A/B/Sun.scala
        |package A.<<B>>
        |object Sun 
        |/$prefix/X/Moon.scala
        |package X
        |import A.{<<B>> => BB}
        |
        |object Moon {
        |  println(BB.Sun)
        |}
        |""".stripMargin,
    fileRenames = Map(s"$prefix/A/B" -> s"$prefix/A/C"),
    expectedRenames = Map("B" -> "C"),
    sourcesAreCompiled = true,
  )
}
