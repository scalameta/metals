"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[7751],{7016:(e,n,s)=>{s.r(n),s.d(n,{assets:()=>r,contentTitle:()=>o,default:()=>h,frontMatter:()=>a,metadata:()=>l,toc:()=>c});var i=s(4848),t=s(8453);const a={author:"\xd3lafur P\xe1ll Geirsson",title:"Fast goto definition with low memory footprint",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"},o=void 0,l={permalink:"/metals/blog/2018/12/12/fast-goto-definition",source:"@site/blog/2018-12-12-fast-goto-definition.md",title:"Fast goto definition with low memory footprint",description:"Metals throws away its navigation index when it shuts down. Next time it starts,",date:"2018-12-12T00:00:00.000Z",tags:[],readingTime:8.42,hasTruncateMarker:!0,authors:[{name:"\xd3lafur P\xe1ll Geirsson",url:"https://twitter.com/olafurpg",imageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"}],frontMatter:{author:"\xd3lafur P\xe1ll Geirsson",title:"Fast goto definition with low memory footprint",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"},unlisted:!1,prevItem:{title:"Metals v0.3.2 - Iron",permalink:"/metals/blog/2018/12/14/iron"},nextItem:{title:"Metals v0.3 - Iron",permalink:"/metals/blog/2018/12/06/iron"}},r={authorsImageUrls:[void 0]},c=[{value:"Problem statement",id:"problem-statement",level:2},{value:"Java",id:"java",level:3},{value:"Scala",id:"scala",level:3},{value:"Initial solution",id:"initial-solution",level:2},{value:"Optimized solution",id:"optimized-solution",level:2},{value:"Evaluation",id:"evaluation",level:2},{value:"Conclusion",id:"conclusion",level:2}];function d(e){const n={a:"a",blockquote:"blockquote",code:"code",h2:"h2",h3:"h3",img:"img",li:"li",ol:"ol",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,t.R)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(n.p,{children:"Metals throws away its navigation index when it shuts down. Next time it starts,\nthe index is computed again from scratch. Although this approach is simple, it\nrequires indexing to be fast enough so you don't mind running it again and\nagain. Also, because we don't persist the index to disk, we need to be careful\nwith memory usage."}),"\n",(0,i.jsx)(n.p,{children:"This post covers how Metals achieves fast source indexing for Scala with a small\nmemory footprint. We describe the problem statement, explain the initial\nsolution and how an optimization delivered a 10x speedup. Finally, we evaluate\nthe result on a real-world project."}),"\n",(0,i.jsxs)(n.p,{children:["The work presented in this post was done as part of my job at the\n",(0,i.jsx)(n.a,{href:"https://scala.epfl.ch/",children:"Scala Center"}),"."]}),"\n",(0,i.jsx)(n.h2,{id:"problem-statement",children:"Problem statement"}),"\n",(0,i.jsxs)(n.p,{children:["What happens when you run Goto Definition? In reality, a lot goes on but in this\npost we're gonna focus on a specific problem: given a method like\n",(0,i.jsx)(n.code,{children:"scala.Some.isEmpty"})," and many thousand source files with millions of lines of\ncode, how do we quickly find the source file that defines that method?"]}),"\n",(0,i.jsx)(n.p,{children:(0,i.jsx)(n.img,{src:"https://user-images.githubusercontent.com/1408093/49591684-67070700-f96f-11e8-873d-90c40480528b.gif",alt:"goto-definition"})}),"\n",(0,i.jsx)(n.p,{children:"There are some hard constraints:"}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsx)(n.li,{children:"we must answer quickly, normal requests should respond within 100-200ms."}),"\n",(0,i.jsx)(n.li,{children:"memory usage should not exceed 10-100Mb since we also need memory to implement\nother features and we're sharing the computer with other applications."}),"\n",(0,i.jsx)(n.li,{children:"computing an index should not take more than ~10 seconds after importing the\nbuild, even for large projects with millions of lines of source code\n(including dependencies)."}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"To keep things simple, imagine we have all source files available in a directory\nthat we can walk and read."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-scala",children:'walk()\n// Stream("scala/Option.scala", "scala/Predef.scala", ...)\n\nread("scala/Option.scala")\n// "sealed abstract class Option { ... }; class Some extends Option { ... }"\n'})}),"\n",(0,i.jsx)(n.h3,{id:"java",children:"Java"}),"\n",(0,i.jsx)(n.p,{children:"For Java, the challenge is not so difficult since the compiler enforces that\neach source file contains a single public class with matching filename."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-java",children:"// java/nio/file/Files.java\npackage java.nio.file;\npublic class Files {\n    public static byte[] readAllBytes(path: Path) {\n      // ...\n    }\n}\n"})}),"\n",(0,i.jsxs)(n.p,{children:["To respond to a Goto Definition request for the ",(0,i.jsx)(n.code,{children:"Files.readAllBytes"})," method, we"]}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsxs)(n.li,{children:["take the enclosing toplevel class ",(0,i.jsx)(n.code,{children:"java.nio.file.Files"})]}),"\n",(0,i.jsxs)(n.li,{children:["read the corresponding file ",(0,i.jsx)(n.code,{children:"java/nio/File/Files.java"})]}),"\n",(0,i.jsxs)(n.li,{children:["parse ",(0,i.jsx)(n.code,{children:"Files.java"})," to find the exact position of ",(0,i.jsx)(n.code,{children:"readAllBytes"})]}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"This approach is fast (parsing one file is cheap) and it also requires no index\n(0Mb memory!)."}),"\n",(0,i.jsx)(n.h3,{id:"scala",children:"Scala"}),"\n",(0,i.jsx)(n.p,{children:"For Scala, the problem is trickier because the compiler allows multiple toplevel\nclasses in the same source file."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-scala",children:"// scala/Option.scala\npackage scala\nsealed abstract class Option[+T] { /* ... */ }\nfinal class Some[+T](value: T) extends Option[T] {\n  def isEmpty = false\n  // ...\n}\n"})}),"\n",(0,i.jsxs)(n.p,{children:["To navigate to the ",(0,i.jsx)(n.code,{children:"scala.Some.isEmpty"})," method we can't use the same approach as\nin Java because the class ",(0,i.jsx)(n.code,{children:"scala.Some"})," is in ",(0,i.jsx)(n.code,{children:"scala/Option.scala"}),", not\n",(0,i.jsx)(n.code,{children:"scala/Some.scala"}),"."]}),"\n",(0,i.jsxs)(n.p,{children:["One possible solution is to read the ",(0,i.jsx)(n.code,{children:"scala/Some.class"})," classfile that contains\nthe filename ",(0,i.jsx)(n.code,{children:"Option.scala"})," where ",(0,i.jsx)(n.code,{children:"scala.Some"})," is defined."]}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:'$ javap -classpath $(coursier fetch org.scala-lang:scala-library:2.12.8) scala.Some\nCompiled from "Option.scala"\n...\n'})}),"\n",(0,i.jsx)(n.p,{children:"However, source information in classfiles is not always reliable and it may be\nremoved by tools that process jar files. Let's restrict the problem to only use\nsource files."}),"\n",(0,i.jsx)(n.p,{children:"Instead of using JVM classfiles, we can walk all Scala source files and build an\nindex that maps toplevel classes to the source file that defines that class. The\nindex looks something like this:"}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-scala",children:'val index = Map[Symbol, Path](\n  Symbol("scala.Some") -> Path("scala/Option.scala"),\n  // ...\n)\n'})}),"\n",(0,i.jsxs)(n.p,{children:["With this index, we find the definition of ",(0,i.jsx)(n.code,{children:"Some.isEmpty"})," using the same steps\nas for ",(0,i.jsx)(n.code,{children:"Files.readAllBytes"})," in Java:"]}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsxs)(n.li,{children:["take the enclosing toplevel class ",(0,i.jsx)(n.code,{children:"scala.Some"})]}),"\n",(0,i.jsxs)(n.li,{children:["query index to know that ",(0,i.jsx)(n.code,{children:"scala.Some"})," is defined in ",(0,i.jsx)(n.code,{children:"scala/Option.scala"})]}),"\n",(0,i.jsxs)(n.li,{children:["parse ",(0,i.jsx)(n.code,{children:"scala/Option.scala"})," to find exact position of ",(0,i.jsx)(n.code,{children:"isEmpty"})," method."]}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"The challenge is to efficiently build the index."}),"\n",(0,i.jsx)(n.h2,{id:"initial-solution",children:"Initial solution"}),"\n",(0,i.jsxs)(n.p,{children:["One approach to build the index is to use the\n",(0,i.jsx)(n.a,{href:"https://scalameta.org/",children:"Scalameta"})," parser to extract the toplevel classes of\neach source file. This parser does not desugar the original code making it\nuseful for refactoring and code-formatting tools like\n",(0,i.jsx)(n.a,{href:"http://scalacenter.github.io/scalafix/",children:"Scalafix"}),"/",(0,i.jsx)(n.a,{href:"http://scalameta.org/scalafmt",children:"Scalafmt"}),".\nI'm also familiar with Scalameta parser API so it was fast to get a working\nimplementation. However, is the parser fast enough to parse millions of lines of\ncode on every server startup?"]}),"\n",(0,i.jsx)(n.p,{children:'According to JMH benchmarks, the Scalameta parser handles ~92k lines/second\nmeasured against a sizable corpus of Scala code. The benchmarks use the\n"single-shot" mode of JMH, for which the documentation says:'}),"\n",(0,i.jsxs)(n.blockquote,{children:["\n",(0,i.jsx)(n.p,{children:'"This mode is useful to estimate the "cold" performance when you don\'t want to\nhide the warmup invocations."'}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"Cold performance is an OK estimate for our use-case since indexing happens\nduring server startup."}),"\n",(0,i.jsx)(n.p,{children:"The Scala standard library is ~36k lines so at 92k lines/second this solution\nscales up to a codebase with up to 20-30 similarly-sized library dependencies.\nIf we add more library dependencies, we exceed the 10 second constraint for\nindexing time. For a codebase with 5 million lines of code, users might have to\nwait one minute for indexing to complete. We should aim for better."}),"\n",(0,i.jsx)(n.h2,{id:"optimized-solution",children:"Optimized solution"}),"\n",(0,i.jsx)(n.p,{children:"We can speed up indexing by writing a custom parser that extracts only the\ninformation we need from a source file. For example, the Scalameta parser\nextracts method implementations that are irrelevant for our indexer. Our indexer\nneeds to know the toplevel classes and nothing more."}),"\n",(0,i.jsx)(n.p,{children:"The simplified algorithm for this custom parser goes something like this:"}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsx)(n.li,{children:"tokenize source file"}),"\n",(0,i.jsxs)(n.li,{children:["on consecutive ",(0,i.jsx)(n.code,{children:"package object"})," keywords, record package object"]}),"\n",(0,i.jsxs)(n.li,{children:["on ",(0,i.jsx)(n.code,{children:"package"})," keyword, record package name"]}),"\n",(0,i.jsxs)(n.li,{children:["on ",(0,i.jsx)(n.code,{children:"class"})," and ",(0,i.jsx)(n.code,{children:"trait"})," and ",(0,i.jsx)(n.code,{children:"object"})," keywords, record toplevel class"]}),"\n",(0,i.jsxs)(n.li,{children:["on ",(0,i.jsx)(n.code,{children:"("})," and ",(0,i.jsx)(n.code,{children:"["})," and ",(0,i.jsx)(n.code,{children:"{"})," delimiters, skip tokens until we find matching closing\ndelimiter"]}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"There are a few more cases to handle, but the implementation ended up being ~200\nlines of code that took an afternoon to write and test (less time than it took\nto write this blog post!)."}),"\n",(0,i.jsx)(n.p,{children:"Benchmarks show that the custom parser is almost 10x faster compared to the\ninitial solution."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-text",children:"[info] Benchmark                  Mode  Cnt  Score   Error  Units\n[info] MetalsBench.toplevelParse    ss   30  0.349 \xb1 0.003   s/op\n[info] MetalsBench.scalametaParse   ss   30  3.370 \xb1 0.175   s/op\n"})}),"\n",(0,i.jsx)(n.p,{children:"At ~920k lines/second it takes ~6 seconds to index a codebase with 5 million\nlines of code, a noticeable improvement to the user experience compared to the\none minute it took with the previous solution."}),"\n",(0,i.jsx)(n.h2,{id:"evaluation",children:"Evaluation"}),"\n",(0,i.jsxs)(n.p,{children:["Micro-benchmarks are helpful but they don't always reflect user experience. To\nevaluate how our indexer performs in the real world, we test Metals on the\n",(0,i.jsx)(n.a,{href:"https://www.prisma.io/",children:"Prisma"})," codebase. Prisma is a server\xa0implemented in\nScala that replaces traditional ORMs and data access layers with a universal\ndatabase abstraction."]}),"\n",(0,i.jsx)(n.p,{children:(0,i.jsx)(n.img,{src:"https://user-images.githubusercontent.com/1408093/49875321-08cf9d80-fe21-11e8-9f02-54ff4960a7af.png",alt:"prisma"})}),"\n",(0,i.jsx)(n.p,{children:"The project has around 80k lines of Scala code."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"$ git clone https://github.com/prisma/prisma.git\n$ cd prisma/server\n$ loc\nLanguage             Files        Lines        Blank      Comment         Code\nScala                  673        88730        12811         1812        74107\n"})}),"\n",(0,i.jsxs)(n.p,{children:["We open VS Code with the Metals extension, enable the server property\n",(0,i.jsx)(n.code,{children:"-Dmetals.statistics=all"})," and import the build."]}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"$ tail -f .metals/metals.log\ntime: ran 'sbt bloopInstall' in 1m4s\ntime: imported workspace in 13s\nmemory: index using 13.1M (923,085 lines Scala)\n"})}),"\n",(0,i.jsx)(n.p,{children:'We run "Import build" again to get a second measurement.'}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-sh",children:"time: ran 'sbt bloopInstall' in 41s\ntime: imported workspace in 7s\nmemory: index using 13.1M (990,144 Scala)\n"})}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.strong,{children:"\"ran 'sbt bloopInstall'\""}),": the time it takes to dump the sbt build\nstructure with ",(0,i.jsx)(n.a,{href:"https://scalacenter.github.io/bloop/",children:"Bloop"}),", the build server\nused by Metals.","\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsx)(n.li,{children:"The Prisma build has 44 sbt projects."}),"\n",(0,i.jsxs)(n.li,{children:["Running ",(0,i.jsx)(n.code,{children:"sbt"})," takes 23 seconds to reach the sbt shell."]}),"\n"]}),"\n"]}),"\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.strong,{children:'"imported workspace"'}),": the time it takes to import the build structure into\nMetals and index sources of all projects, including the following steps:","\n",(0,i.jsxs)(n.ol,{children:["\n",(0,i.jsx)(n.li,{children:"Query Bloop for the dumped build structure, listing all project\ndependencies, sources and compiler options."}),"\n",(0,i.jsx)(n.li,{children:"Walk, read and index all Scala sources in the workspace, happens in both\nruns."}),"\n",(0,i.jsxs)(n.li,{children:["Walk, read and index all library dependency sources, happens only for the\nfirst run since the indexer caches\xa0",(0,i.jsx)(n.code,{children:"*-sources.jar"})," file results."]}),"\n"]}),"\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsx)(n.li,{children:'It\'s expected that "lines of code" increased by ~70k lines in the second run\nsince we indexed the workspace sources again.'}),"\n",(0,i.jsxs)(n.li,{children:["It's expected that indexing was faster in the second run since indexes are\ncached for ",(0,i.jsx)(n.code,{children:"*-sources.jar"})," files and the JVM has also warmed up."]}),"\n"]}),"\n"]}),"\n",(0,i.jsxs)(n.li,{children:[(0,i.jsx)(n.strong,{children:'"index using 13.1M"'}),": the memory footprint of the index mapping toplevel\nScala classes to which file they are defined in.","\n",(0,i.jsxs)(n.ul,{children:["\n",(0,i.jsxs)(n.li,{children:["Memory is measured using\n",(0,i.jsx)(n.a,{href:"https://openjdk.java.net/projects/code-tools/jol/",children:"OpenJDK JOL"}),"\n",(0,i.jsx)(n.code,{children:"GraphLayout.totalSize()"}),"."]}),"\n",(0,i.jsx)(n.li,{children:"Note that the server uses more memory in total, this is only for the\nnavigation index."}),"\n",(0,i.jsx)(n.li,{children:"The index does not contain entries where the toplevel classname matches the\nfilename, for the same reason we don't index Java sources."}),"\n"]}),"\n"]}),"\n"]}),"\n",(0,i.jsx)(n.p,{children:"On average, Goto Definition responds well within our 100ms goal."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-text",children:"time: definition in 0.03s\ntime: definition in 0.02s\n"})}),"\n",(0,i.jsx)(n.p,{children:"However, occasional definition requests take much longer to respond."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-text",children:"info  time: definition in 8s\ninfo  time: definition in 0.62s\n"})}),"\n",(0,i.jsx)(n.p,{children:"The outliers happen when navigating inside library dependency sources, which is\nexpected to be slower since we need to type-check those sources before querying\nthe index."}),"\n",(0,i.jsx)(n.h2,{id:"conclusion",children:"Conclusion"}),"\n",(0,i.jsx)(n.p,{children:"Metals uses a custom Scala parser to index sources at roughly 1 million\nlines/second in micro-benchmarks. The index is used to quickly respond to Goto\nDefinition requests. On a case-study project containing 44 modules and ~900k\nlines of Scala sources (including library dependencies), it takes ~10 seconds to\nimport the build structure (including source indexing) and the resulting index\nuses 13M memory."}),"\n",(0,i.jsx)(n.p,{children:"The Java+Scala source indexers used by Metals are available in a standalone\nlibrary called mtags."}),"\n",(0,i.jsx)(n.pre,{children:(0,i.jsx)(n.code,{className:"language-scala",children:'libraryDependencies += "org.scalameta" %% "mtags" % "0.3.1"\n'})}),"\n",(0,i.jsxs)(n.p,{children:["The mtags library is already being used by the\n",(0,i.jsx)(n.a,{href:"http://almond-sh.github.io/almond/stable/docs/intro",children:"Almond Scala kernel for Jupyter"}),"\nto support Goto Definition inside Jupyter notebooks."]}),"\n",(0,i.jsx)(n.p,{children:"Can the indexer become faster? Sure, I suspect there's still room for 2-3x\nspeedups with further optimizations. However, I'm not convinced it will make a\nsignificant improvement to the user experience since we remain bottle-necked by\ndumping sbt build structure and compiling the sources."}),"\n",(0,i.jsxs)(n.p,{children:["Try out Goto Definition with Metals today using VS Code, Atom, Vim, Sublime Text\nor Emacs using the installation instructions here:\n",(0,i.jsx)(n.a,{href:"https://scalameta.org/metals/docs/editors/overview.html",children:"https://scalameta.org/metals/docs/editors/overview.html"}),'. The indexer is working\nwhen you see "Importing build" in the status bar\n',(0,i.jsx)(n.img,{src:"https://user-images.githubusercontent.com/1408093/49924982-f5234600-feb7-11e8-9edd-715388bb546f.gif",alt:"imageedit_3_3576623823"})]})]})}function h(e={}){const{wrapper:n}={...(0,t.R)(),...e.components};return n?(0,i.jsx)(n,{...e,children:(0,i.jsx)(d,{...e})}):d(e)}},8453:(e,n,s)=>{s.d(n,{R:()=>o,x:()=>l});var i=s(6540);const t={},a=i.createContext(t);function o(e){const n=i.useContext(a);return i.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function l(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(t):e.components||t:o(e.components),i.createElement(a.Provider,{value:n},e.children)}}}]);