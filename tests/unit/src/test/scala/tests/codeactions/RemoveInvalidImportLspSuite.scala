package tests.codeactions

import scala.meta.internal.metals.codeactions.RemoveInvalidImport
import scala.meta.internal.metals.codeactions.RemoveInvalidImportQuickFix
import scala.meta.internal.metals.codeactions.SourceRemoveInvalidImports

import org.eclipse.{lsp4j => l}

class RemoveInvalidImportLspSuite
    extends BaseCodeActionLspSuite("removeInvalidSymbol") {

  // ---------------------------------------------------------------------------
  // Tests for RemoveInvalidImportQuickFix (CodeActionKind.QuickFix)
  // These tests verify the existing behavior for manual import resolution
  // ---------------------------------------------------------------------------

  check(
    "entire-import-1-fix",
    """|package a
       |
       |<<import scala.collection.Seq
       |import scala.collection.DoesntExist>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("DoesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "entire-import-2-fix",
    """|package a
       |
       |<<import scala.collection.Seq
       |import scala.collection.doesntExist.List>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("doesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "entire-import-3-fix",
    """|package a
       |
       |<<import scala.collection.Seq
       |import scala.collection.doesntExist.{List, LazyList}>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("doesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-importer-1-fix",
    """|package a
       |
       |<<import scala.doesntExist.Baz, scala.collection.Seq>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("doesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-importer-2-fix",
    """|package a
       |
       |<<import scala.collection.Seq, scala.doesntExist.Baz>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("doesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-importer-3-fix",
    """|package a
       |
       |<<import scala.collection.DoesntExist, scala.doesntExist.Baz>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImportQuickFix.allImportsTitle}
        |${RemoveInvalidImport.title("DoesntExist")}
        |${RemoveInvalidImport.title("doesntExist")}
        |""".stripMargin,
    """|package a
       |
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-importer-4-fix",
    """|package a
       |
       |import scala.collection.{DoesntExist1, <<DoesntExist2}, scala.doesn>>tExist.Baz
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImportQuickFix.allImportsTitle}
        |${RemoveInvalidImport.title("DoesntExist2")}
        |${RemoveInvalidImport.title("doesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{DoesntExist1}
       |
       |object A
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-selector-1-fix",
    """|package a
       |
       |<<import scala.collection.{DoesntExist, Seq, IndexedSeq}>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("DoesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{Seq, IndexedSeq}
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-selector-2-fix",
    """|package a
       |
       |<<import scala.collection.{Seq, DoesntExist, IndexedSeq}>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("DoesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{Seq, IndexedSeq}
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-selector-3-fix",
    """|package a
       |
       |<<import scala.collection.{Seq, IndexedSeq, DoesntExist}>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("DoesntExist")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{Seq, IndexedSeq}
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-selector-4-fix",
    """|package a
       |
       |<<import scala.collection.{DoesntExist1, DoesntExist2}>>
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImportQuickFix.allImportsTitle}
        |${RemoveInvalidImport.title("DoesntExist1")}
        |${RemoveInvalidImport.title("DoesntExist2")}
        |""".stripMargin,
    """|package a
       |
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "in-selector-5-fix",
    """|package a
       |
       |import scala.<<collection.{DoesntExist1>>, DoesntExist2}
       |
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImport.title("DoesntExist1")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{DoesntExist2}
       |
       |object A
       |""".stripMargin,
    expectNoDiagnostics = false,
    kind = List(l.CodeActionKind.QuickFix),
  )

  check(
    "involved1-fix",
    """|package a
       |<<
       |import scala.sys.doesntExit.other
       |import scala.collection.{DoesntExist1, AbstractSet, DoesntExist2}, scala.collection.DoesntExist3, scala.foo.DoesntExist4
       |import doesnt.exist
       |import _root_.scala.concurrent.Future
       |>>
       |object A
       |""".stripMargin,
    s"""|${RemoveInvalidImportQuickFix.allImportsTitle}
        |${RemoveInvalidImport.title("doesntExit")}
        |${RemoveInvalidImport.title("DoesntExist1")}
        |${RemoveInvalidImport.title("DoesntExist2")}
        |${RemoveInvalidImport.title("DoesntExist3")}
        |${RemoveInvalidImport.title("foo")}
        |${RemoveInvalidImport.title("doesnt")}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{AbstractSet}
       |import _root_.scala.concurrent.Future
       |
       |object A
       |""".stripMargin,
    kind = List(l.CodeActionKind.QuickFix),
    filterAction = action => action.getTitle().contains("invalid import"),
  )

  // ---------------------------------------------------------------------------
  // Tests for SourceRemoveInvalidImports (CodeActionKind.Source)
  // These tests verify the existing behavior for manual import resolution
  // ---------------------------------------------------------------------------

  check(
    "entire-import-1-source",
    """|package a
       |
       |import scala.collection.Seq
       |import scala.collection.DoesntExist
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "entire-import-2-source",
    """|package a
       |
       |import scala.collection.Seq
       |import scala.collection.doesntExist.List
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "entire-import-3-source",
    """|package a
       |
       |import scala.collection.Seq
       |import scala.collection.doesntExist.{List, LazyList}
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "in-importer-1-source",
    """|package a
       |
       |import scala.doesntExist.Baz, scala.collection.Seq
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "in-importer-2-source",
    """|package a
       |
       |import scala.collection.Seq, scala.doesntExist.Baz
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.Seq
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "in-importer-3-source",
    """|package a
       |
       |import scala.collection.DoesntExist, scala.doesntExist.Baz
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "in-selector-1-source",
    """|package a
       |
       |import scala.collection.{DoesntExist, Seq, IndexedSeq}
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{Seq, IndexedSeq}
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "in-selector-2-source",
    """|package a
       |
       |import scala.collection.{Seq, DoesntExist, IndexedSeq}
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{Seq, IndexedSeq}
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "in-selector-3-source",
    """|package a
       |
       |import scala.collection.{Seq, IndexedSeq, DoesntExist}
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{Seq, IndexedSeq}
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "in-selector-4-source",
    """|package a
       |
       |import scala.collection.{DoesntExist1, DoesntExist2}
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  check(
    "involved1-source",
    """|package a
       |
       |import scala.sys.doesntExit.other
       |import scala.collection.{DoesntExist1, AbstractSet, DoesntExist2}, scala.collection.DoesntExist3, scala.foo.DoesntExist4
       |import doesnt.exist
       |import _root_.scala.concurrent.Future
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{AbstractSet}
       |import _root_.scala.concurrent.Future
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
  )

  // After 2.13.17, `DoesntExist2 => other` should also be removed
  check(
    "renames",
    """|package a
       |
       |import scala.collection.{DoesntExist1, AbstractSet, DoesntExist2 => other}
       |
       |object <<A>>
       |""".stripMargin,
    s"""|${SourceRemoveInvalidImports.title}
        |""".stripMargin,
    """|package a
       |
       |import scala.collection.{AbstractSet, DoesntExist2 => other}
       |
       |object A
       |""".stripMargin,
    kind = List(SourceRemoveInvalidImports.kind),
    expectNoDiagnostics = false,
  )
}
