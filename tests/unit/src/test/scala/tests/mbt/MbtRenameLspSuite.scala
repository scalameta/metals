package tests.mbt

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BaseRenameLspSuite
import tests.BuildInfo
import tests.TestingServer

class MbtRenameLspSuite extends BaseRenameLspSuite(s"rename") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  override def initializeGitRepo: Boolean = true

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  override def useMbt: Boolean = true
  override def defaultMbtJson: String =
    """|{
       |  "namespaces" : {
       |    "a" : {
       |      "sources": ["a/src/main/java/**", "a/src/main/scala/**"]
       |    }
       |  }
       |}""".stripMargin

  renamed(
    "basic",
    """|/a/src/main/scala/a/Main.scala
       |package a
       |object Main{
       |  val <<toRename>> = 123
       |}
       |/a/src/main/scala/a/Main2.scala
       |package a
       |object Main2{
       |  val toRename = Main.<<toR@@ename>>
       |}
       |""".stripMargin,
    newName = "otherRename",
  )

  renamed(
    "java",
    """|/a/src/main/java/a/Other.java
       |package a;
       |public class <<Other>>{
       |
       |  <<Other>> other;
       |  public <<Other>>(){
       |
       |  }
       |}
       |/a/src/main/java/a/Main.java
       |package a;
       |public class Main{
       |  <<Other>> other = new <<Oth@@er>>();
       |}
       |""".stripMargin,
    newName = "Renamed",
    fileRenames =
      Map("a/src/main/java/a/Other.java" -> "a/src/main/java/a/Renamed.java"),
  )

  renamed(
    "java-constructor",
    """|/a/src/main/java/a/Other.java
       |package a;
       |public class <<Other>>{
       |
       |  <<Other>> other;
       |  public <<Other>>(){
       |    this(42);
       |  }
       |  public <<Other>>(int x){
       |    this.x = x;
       |  }
       |  
       |  public <<Other>>(int x, String type)
       |  {
       |    this();
       |  }
       |
       |  private int x;
       |}
       |/a/src/main/java/a/Main.java
       |package a;
       |public class Main{
       |  <<Other>> other = new <<Oth@@er>>();
       |}
       |""".stripMargin,
    newName = "Renamed",
    fileRenames =
      Map("a/src/main/java/a/Other.java" -> "a/src/main/java/a/Renamed.java"),
  )

}
