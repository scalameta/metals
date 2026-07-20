package tests

object JavaModuleExports {

  val options: List[String] = List(
    // javac internal packages
    "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.resources=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    // com.sun.source public APIs (sometimes need explicit exports)
    "--add-exports=jdk.compiler/com.sun.source.doctree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.source.tree=ALL-UNNAMED",
    "--add-exports=jdk.compiler/com.sun.source.util=ALL-UNNAMED",
    // java.base internal utilities
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.loader=ALL-UNNAMED",
    "--add-exports=java.base/sun.security.util=ALL-UNNAMED",
    "--add-exports=java.base/sun.nio.ch=ALL-UNNAMED",
    // javadoc tools
    "--add-exports=jdk.javadoc/jdk.javadoc.internal.tool=ALL-UNNAMED",
    "--add-exports=jdk.javadoc/com.sun.tools.javadoc.main=ALL-UNNAMED"
  )
}
