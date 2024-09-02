/*! For license information please see b04d5738.19c4b9cf.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[1856],{4103:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>o,contentTitle:()=>l,default:()=>d,frontMatter:()=>r,metadata:()=>a,toc:()=>h});var i=s(4848),n=s(8453);const r={author:"\xd3lafur P\xe1ll Geirsson",title:"Metals v0.7.0 - Thorium",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://github.com/olafurpg.png"},l=void 0,a={permalink:"/metals/blog/2019/06/28/thorium",source:"@site/blog/2019-06-28-thorium.md",title:"Metals v0.7.0 - Thorium",description:'We are excited to announce the release of Metals v0.7.0 - codename "Thorium" \ud83c\udf89',date:"2019-06-28T00:00:00.000Z",tags:[],readingTime:6.65,hasTruncateMarker:!1,authors:[{name:"\xd3lafur P\xe1ll Geirsson",url:"https://twitter.com/olafurpg",imageURL:"https://github.com/olafurpg.png",key:null,page:null}],frontMatter:{author:"\xd3lafur P\xe1ll Geirsson",title:"Metals v0.7.0 - Thorium",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://github.com/olafurpg.png"},unlisted:!1,prevItem:{title:"Metals v0.7.2 - Thorium",permalink:"/metals/blog/2019/09/02/thorium"},nextItem:{title:"Metals v0.6.1 - Radium",permalink:"/metals/blog/2019/06/11/radium"}},o={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Tree views in VS Code",id:"tree-views-in-vs-code",level:2},{value:"Projects explorer",id:"projects-explorer",level:3},{value:"Libraries explorer",id:"libraries-explorer",level:3},{value:"Reveal active file in Metals side bar",id:"reveal-active-file-in-metals-side-bar",level:3},{value:"Compilation explorer",id:"compilation-explorer",level:3},{value:"Help and feedback explorer",id:"help-and-feedback-explorer",level:3},{value:"Support for Scala 2.13",id:"support-for-scala-213",level:2},{value:"JDK 11 support",id:"jdk-11-support",level:2},{value:"Improved classpath indexing performance",id:"improved-classpath-indexing-performance",level:2},{value:"Fallback to &quot;find references&quot; from &quot;goto definition&quot;",id:"fallback-to-find-references-from-goto-definition",level:2},{value:"Dropping older Scala versions",id:"dropping-older-scala-versions",level:2},{value:"Fixes for auto importing builds",id:"fixes-for-auto-importing-builds",level:2},{value:"No more &quot;work in progress&quot;",id:"no-more-work-in-progress",level:2},{value:"Miscellaneous fixes",id:"miscellaneous-fixes",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.7.0 (2019-06-28)",id:"v070-2019-06-28",level:2}];function c(e){const t={a:"a",code:"code",h2:"h2",h3:"h3",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",table:"table",tbody:"tbody",td:"td",th:"th",thead:"thead",tr:"tr",ul:"ul",...(0,n.R)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(t.p,{children:'We are excited to announce the release of Metals v0.7.0 - codename "Thorium" \ud83c\udf89\nThe release includes several new features along with bug fixes.'}),"\n",(0,i.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Atom, Vim,\nSublime Text and Emacs. Metals is developed at the\n",(0,i.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,i.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nalong with contributors from the community."]}),"\n",(0,i.jsxs)(t.p,{children:["In this release we merged 21 PRs and closed 8 issues, full details:\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/11?closed=1",children:"https://github.com/scalameta/metals/milestone/11?closed=1"})]}),"\n",(0,i.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsx)(t.li,{children:"New tree view in VS Code"}),"\n",(0,i.jsx)(t.li,{children:"New support for Scala 2.13"}),"\n",(0,i.jsx)(t.li,{children:"New support for JDK 11"}),"\n",(0,i.jsx)(t.li,{children:"New improved classpath indexing performance"}),"\n",(0,i.jsx)(t.li,{children:'New fallback to "find references" when calling "goto definition" on a symbol\ndefinition'}),"\n",(0,i.jsx)(t.li,{children:"Bug fixes for importing builds in Gradle, Mill and sbt"}),"\n",(0,i.jsx)(t.li,{children:"Dropped support for deprecated Scala versions 2.11.9, 2.11.10, 2.11.11,\n2.12.4, 2.12.5 and 2.12.6."}),"\n"]}),"\n",(0,i.jsxs)(t.p,{children:["Check out the website and give Metals a try: ",(0,i.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"})]}),"\n",(0,i.jsx)(t.h2,{id:"tree-views-in-vs-code",children:"Tree views in VS Code"}),"\n",(0,i.jsx)(t.p,{children:'There is now a new "Metals" sidebar in VS Code that contains three tree views:'}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsx)(t.li,{children:"Build: overview of the build state, with buttons to manually trigger build\nimport."}),"\n",(0,i.jsx)(t.li,{children:"Compile: overview of ongoing compilations, with buttons to manually cascade\ncompilation and cancel ongoing compilation."}),"\n",(0,i.jsx)(t.li,{children:"Help and feedback: buttons to automate troubleshooting Metals issues and links\nto relevant online resources such as GitHub, Gitter and Twitter."}),"\n"]}),"\n",(0,i.jsx)(t.h3,{id:"projects-explorer",children:"Projects explorer"}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://user-images.githubusercontent.com/1408093/60208747-59589680-9859-11e9-8a09-2a6c38683975.gif",alt:"2019-06-26 20 28 14"})}),"\n",(0,i.jsx)(t.h3,{id:"libraries-explorer",children:"Libraries explorer"}),"\n",(0,i.jsx)(t.p,{children:"Browse symbols that are defined in jars of library dependencies."}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://user-images.githubusercontent.com/1408093/60208786-6e352a00-9859-11e9-8d55-2125c91d506f.gif",alt:"2019-06-26 20 28 51"})}),"\n",(0,i.jsx)(t.h3,{id:"reveal-active-file-in-metals-side-bar",children:"Reveal active file in Metals side bar"}),"\n",(0,i.jsx)(t.p,{children:'There is a new command "Reveal active file in Metals side bar" that focuses the\nlibrary and project explorer to the current open file.'}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://user-images.githubusercontent.com/1408093/60277613-33d19880-98fe-11e9-9aee-71c0a0c0861f.gif",alt:"2019-06-27 16 02 33"})}),"\n",(0,i.jsx)(t.h3,{id:"compilation-explorer",children:"Compilation explorer"}),"\n",(0,i.jsx)(t.p,{children:"Get an overview of all compilations that are ongoing in the build. Previously,\nonly a single compilation progress was reported through the status bar."}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://user-images.githubusercontent.com/1408093/60052207-f4743380-96d4-11e9-9192-f71db4100fe9.gif",alt:"2019-06-23 15 41 07"})}),"\n",(0,i.jsx)(t.h3,{id:"help-and-feedback-explorer",children:"Help and feedback explorer"}),"\n",(0,i.jsx)("img",{width:"295",alt:"Screenshot 2019-06-27 at 15 57 15",src:"https://user-images.githubusercontent.com/1408093/60277560-1ef50500-98fe-11e9-8c02-70b841a590c3.png"}),"\n",(0,i.jsx)(t.h2,{id:"support-for-scala-213",children:"Support for Scala 2.13"}),"\n",(0,i.jsxs)(t.p,{children:["Metals now supports Scala 2.13.0! Please upgrade to Scalafmt v2.0.0 in order to\nformat 2.13-specific syntax such as underscore separators (",(0,i.jsx)(t.code,{children:"1_000_000"}),"). Note\nthat completions may in rare situations not work perfectly for Scala 2.13, in\nparticular:"]}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:["whitebox string interpolator macros, we had to disable one unit test for 2.13.\nSee ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/issues/777",children:"#777"}),"."]}),"\n",(0,i.jsxs)(t.li,{children:["better-monadic-for compile plugin, we had to disable one unit test for 2.13.\nSee ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/issues/777",children:"#777"}),"."]}),"\n"]}),"\n",(0,i.jsxs)(t.p,{children:["Big thanks to ",(0,i.jsx)(t.a,{href:"https://github.com/gabro",children:"@gabro"})," for leading this effort!"]}),"\n",(0,i.jsx)(t.h2,{id:"jdk-11-support",children:"JDK 11 support"}),"\n",(0,i.jsxs)(t.p,{children:["Metals can now run on Java 11! To use Java 11 instead of Java 8, point the\n",(0,i.jsx)(t.code,{children:"$JAVA_HOME"})," environment variable to a Java 11 installation."]}),"\n",(0,i.jsx)(t.p,{children:'The VS Code extension will continue to use Java 8 by default, update the "Java\nHome" setting to use Java 11 instead.'}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://i.imgur.com/F5djfzt.png",alt:""})}),"\n",(0,i.jsx)(t.p,{children:"To obtain the Java 11 home on macOS, use the following command:"}),"\n",(0,i.jsx)(t.pre,{children:(0,i.jsx)(t.code,{className:"language-sh",children:"$ /usr/libexec/java_home 11\n/Library/Java/JavaVirtualMachines/openjdk-11.0.1.jdk/Contents/Home\n"})}),"\n",(0,i.jsxs)(t.p,{children:["Big thanks to ",(0,i.jsx)(t.a,{href:"https://github.com/er1c",children:"@er1c"})," for pushing for the effort on\nboth the Bloop and Metals side to support Java 11!"]}),"\n",(0,i.jsx)(t.h2,{id:"improved-classpath-indexing-performance",children:"Improved classpath indexing performance"}),"\n",(0,i.jsx)(t.p,{children:"Previously, to support fuzzy symbol search Metals indexed classpath elements\nusing an algorithm that required a quadratic iteration on the number of\ncharacters in classfile names. Now, the fuzzy symbol search algorithm only\nrequires a linear pass on the characters of a classfile name. This optimization\nresulted in a 2x speedup for indexing a 235Mb classpath in our benchmarks."}),"\n",(0,i.jsx)(t.pre,{children:(0,i.jsx)(t.code,{className:"language-diff",children:"  Benchmark                   Mode  Cnt     Score    Error  Units\n- ClasspathIndexingBench.run    ss   10  1809.068 \xb1 61.461  ms/op # JDK 8\n+ ClasspathIndexingBench.run    ss   10  919.237  \xb1 42.827  ms/op # JDK 8\n+ ClasspathIndexingBench.run    ss   10  1316.451 \xb1 22.595  ms/op # JDK 11\n"})}),"\n",(0,i.jsx)(t.h2,{id:"fallback-to-find-references-from-goto-definition",children:'Fallback to "find references" from "goto definition"'}),"\n",(0,i.jsx)(t.p,{children:'Previously, nothing happened when invoking "goto definition" on the symbol\ndefinition itself. Now, Metals falls back to "find references" in this\nsituation.'}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://i.imgur.com/BT3h0FJ.gif",alt:"Fallback"})}),"\n",(0,i.jsxs)(t.p,{children:["Big thanks to ",(0,i.jsx)(t.a,{href:"https://github.com/tanishiking",children:"@tanishiking"})," for contributing\nthis new feature!"]}),"\n",(0,i.jsx)(t.h2,{id:"dropping-older-scala-versions",children:"Dropping older Scala versions"}),"\n",(0,i.jsx)(t.p,{children:"The Scala versions supported by Metals are now the following."}),"\n",(0,i.jsxs)(t.table,{children:[(0,i.jsx)(t.thead,{children:(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.th,{style:{textAlign:"left"},children:"Version"}),(0,i.jsx)(t.th,{children:"Old Status"}),(0,i.jsx)(t.th,{children:"New Status"})]})}),(0,i.jsxs)(t.tbody,{children:[(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.11.9"}),(0,i.jsx)(t.td,{children:"Deprecated"}),(0,i.jsx)(t.td,{children:"Dropped"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.11.10"}),(0,i.jsx)(t.td,{children:"Deprecated"}),(0,i.jsx)(t.td,{children:"Dropped"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.11.11"}),(0,i.jsx)(t.td,{children:"Deprecated"}),(0,i.jsx)(t.td,{children:"Dropped"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.11.12"}),(0,i.jsx)(t.td,{children:"Supported"}),(0,i.jsx)(t.td,{children:"Deprecated, with no plans to be dropped in upcoming releases*"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.12.4"}),(0,i.jsx)(t.td,{children:"Deprecated"}),(0,i.jsx)(t.td,{children:"Dropped"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.12.5"}),(0,i.jsx)(t.td,{children:"Deprecated"}),(0,i.jsx)(t.td,{children:"Dropped"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.12.6"}),(0,i.jsx)(t.td,{children:"Deprecated"}),(0,i.jsx)(t.td,{children:"Dropped"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.12.7"}),(0,i.jsx)(t.td,{children:"Supported"}),(0,i.jsx)(t.td,{children:"Deprecated, please upgrade to 2.12.8"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.12.8"}),(0,i.jsx)(t.td,{children:"Supported"}),(0,i.jsx)(t.td,{children:"Supported"})]}),(0,i.jsxs)(t.tr,{children:[(0,i.jsx)(t.td,{style:{textAlign:"left"},children:"2.13.0"}),(0,i.jsx)(t.td,{children:"Unsupported"}),(0,i.jsx)(t.td,{children:"Supported"})]})]})]}),"\n",(0,i.jsx)(t.p,{children:"* Scala 2.11 support will likely stay around for a while given the situation\nwith Spark, Scala Native, Playframework and other libraries and frameworks that\nhave been late to adopt 2.12. Our download numbers show that ~10% of Metals\nusers are still on 2.11. Nevertheless, we encourage our users to upgrade to 2.12\nto enjoy a better code editing experience thanks to multiple improvements in the\ncompiler."}),"\n",(0,i.jsx)(t.h2,{id:"fixes-for-auto-importing-builds",children:"Fixes for auto importing builds"}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:["digest only ",(0,i.jsx)(t.code,{children:".sbt"})," files at workspace level for sbt projects (thanks\n",(0,i.jsx)(t.a,{href:"https://github.com/wojciechUrbanski",children:"@wojciechUrbanski"}),"!)"]}),"\n",(0,i.jsx)(t.li,{children:"fix SemanticDB plugin path on Windows for Gradle workspaces"}),"\n",(0,i.jsx)(t.li,{children:"update default Mill version to 0.4.1 to fix an issue with classpath not\ncontaining Scala library"}),"\n",(0,i.jsxs)(t.li,{children:["Metals now pick up the version from ",(0,i.jsx)(t.code,{children:".mill-version"})," for the ",(0,i.jsx)(t.code,{children:"millw"})," script"]}),"\n"]}),"\n",(0,i.jsx)(t.h2,{id:"no-more-work-in-progress",children:'No more "work in progress"'}),"\n",(0,i.jsx)(t.p,{children:'Previously, the Metals website used the "Work-in-progress language server for\nScala" tagline to reflect the experimental status of the project. Now, the\ntagline on the website has been changed to "Scala language server with rich IDE\nfeatures" to reflect that Metals is used by thousands of developers today for\ntheir day-to-day coding.'}),"\n",(0,i.jsx)(t.h2,{id:"miscellaneous-fixes",children:"Miscellaneous fixes"}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:["fix off-by-one for parameter hints when the cursor was after the closing ",(0,i.jsx)(t.code,{children:")"}),"\nparenthesis"]}),"\n",(0,i.jsx)(t.li,{children:"make sure we add an autoimport in the correct line in case of brackets"}),"\n",(0,i.jsxs)(t.li,{children:["document symbol outline no longer fails when ",(0,i.jsx)(t.code,{children:"val _ = ()"})," is the only thing in\na block expression"]}),"\n",(0,i.jsx)(t.li,{children:"nested objects are now imported correctly in case of deeper nesting"}),"\n",(0,i.jsxs)(t.li,{children:["fix concurrent modification in text document cache (thanks\n",(0,i.jsx)(t.a,{href:"https://github.com/chikei",children:"@chikei"}),"!)"]}),"\n"]}),"\n",(0,i.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,i.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release, it's amazing we had a\ncouple of new contributors to Metals!"}),"\n",(0,i.jsx)(t.pre,{children:(0,i.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.6.1..v0.7.0\nGabriele Petronella\n\xd3lafur P\xe1ll Geirsson\nTomasz Godzik\nEric Peters\ntanishiking\nRuben Berenguel\nTzeKei Lee\nWojciech Urbanski\n"})}),"\n",(0,i.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,i.jsxs)(t.h2,{id:"v070-2019-06-28",children:[(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.7.0",children:"v0.7.0"})," (2019-06-28)"]}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.6.1...v0.7.0",children:"Full Changelog"})}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:["Upgrade to Scalameta v4.2.0\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/799",children:"#799"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Introduce Tree View Protocol\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/797",children:"#797"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add a benchmark for classpath indexing performance.\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/795",children:"#795"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Migrate Java converters to 2.13\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/794",children:"#794"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Remove dot as a commit character\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/793",children:"#793"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Make classpath indexing linear instead of quadratic.\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/792",children:"#792"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Use 2.13 dialect for syntax errors\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/789",children:"#789"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix off-by-one for parameter hints.\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/786",children:"#786"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Make sure we add an autoimport in the correct line\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/785",children:"#785"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix typo ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/784",children:"#784"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Remove Work-in-progress from the tagline\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/782",children:"#782"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix JdkSources not to ignore JAVA_HOME environment variable\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/781",children:"#781"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix importing nested objects\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/778",children:"#778"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix an issue when ",(0,i.jsx)(t.code,{children:"val \\_ = \\(\\)"})," is the only thing in the block\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/776",children:"#776"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:['Fallback to "show usages" from "Goto definition" if the symbol represents a\ndefinition itself ',(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/775",children:"#775"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Move slow tests to separate directories and run them separately on Travis\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/773",children:"#773"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Digest only sbt files at workspace level\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/772",children:"#772"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/wojciechUrbanski",children:"wojciechUrbanski"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix wrong windows SemanticDB file path\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/771",children:"#771"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix concurrent modification\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/766",children:"#766"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/chikei",children:"chikei"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Factor out a ClasspathLoader.getURLs(classLoader) helper for java9+\ncompatability, update usages\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/765",children:"#765"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/er1c",children:"er1c"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Include mill version from .mill-version file\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/764",children:"#764"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add 2.13 support and drop deprecated versions\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/763",children:"#763"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Clarifiyng a setting ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/759",children:"#759"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/rberenguel",children:"rberenguel"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix very small typo in docs\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/758",children:"#758"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/rberenguel",children:"rberenguel"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix completions to compilation in blog\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/757",children:"#757"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update VS Code docs ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/756",children:"#756"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add release notes for Metals Radium release\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/752",children:"#752"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,n.R)(),...e.components};return t?(0,i.jsx)(t,{...e,children:(0,i.jsx)(c,{...e})}):c(e)}},1020:(e,t,s)=>{var i=s(6540),n=Symbol.for("react.element"),r=Symbol.for("react.fragment"),l=Object.prototype.hasOwnProperty,a=i.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,o={key:!0,ref:!0,__self:!0,__source:!0};function h(e,t,s){var i,r={},h=null,c=null;for(i in void 0!==s&&(h=""+s),void 0!==t.key&&(h=""+t.key),void 0!==t.ref&&(c=t.ref),t)l.call(t,i)&&!o.hasOwnProperty(i)&&(r[i]=t[i]);if(e&&e.defaultProps)for(i in t=e.defaultProps)void 0===r[i]&&(r[i]=t[i]);return{$$typeof:n,type:e,key:h,ref:c,props:r,_owner:a.current}}t.Fragment=r,t.jsx=h,t.jsxs=h},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>l,x:()=>a});var i=s(6540);const n={},r=i.createContext(n);function l(e){const t=i.useContext(r);return i.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function a(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:l(e.components),i.createElement(r.Provider,{value:t},e.children)}}}]);