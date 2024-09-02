/*! For license information please see 4a409fa0.b5385cbf.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[9692],{5713:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>h,contentTitle:()=>r,default:()=>d,frontMatter:()=>l,metadata:()=>n,toc:()=>c});var a=s(4848),i=s(8453);const l={author:"Katarzyna Marek",title:"Metals v1.3.2 - Thallium",authorImageURL:"https://github.com/kasiaMarek.png"},r=void 0,n={permalink:"/metals/blog/2024/06/19/thallium",source:"@site/blog/2024-06-19-thallium.md",title:"Metals v1.3.2 - Thallium",description:"We're excited to announce the release of Metals v1.3.2. This new version not only addresses numerous bugs but also enhances the experience of working with code that does not compile.",date:"2024-06-19T00:00:00.000Z",tags:[],readingTime:8.245,hasTruncateMarker:!1,authors:[{name:"Katarzyna Marek",imageURL:"https://github.com/kasiaMarek.png",key:null,page:null}],frontMatter:{author:"Katarzyna Marek",title:"Metals v1.3.2 - Thallium",authorImageURL:"https://github.com/kasiaMarek.png"},unlisted:!1,prevItem:{title:"Metals v1.3.3 - Thallium",permalink:"/metals/blog/2024/07/12/thallium"},nextItem:{title:"Metals v1.3.1 - Thallium",permalink:"/metals/blog/2024/05/16/thallium"}},h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Outline compilation",id:"outline-compilation",level:2},{value:"Use presentation compiler as fallback for references search",id:"use-presentation-compiler-as-fallback-for-references-search",level:2},{value:"Allow to override debug server startup timeout",id:"allow-to-override-debug-server-startup-timeout",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v1.3.2 (2024-06-19)",id:"v132-2024-06-19",level:2}];function o(e){const t={a:"a",code:"code",em:"em",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,i.R)(),...e.components};return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsx)(t.p,{children:"We're excited to announce the release of Metals v1.3.2. This new version not only addresses numerous bugs but also enhances the experience of working with code that does not compile."}),"\n",(0,a.jsx)("table",{children:(0,a.jsxs)("tbody",{children:[(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Commits since last release"}),(0,a.jsx)("td",{align:"center",children:"87"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Merged PRs"}),(0,a.jsx)("td",{align:"center",children:"82"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Contributors"}),(0,a.jsx)("td",{align:"center",children:"13"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Closed issues"}),(0,a.jsx)("td",{align:"center",children:"26"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"New features"}),(0,a.jsx)("td",{align:"center",children:"1"})]})]})}),"\n",(0,a.jsxs)(t.p,{children:["For full details: ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/68?closed=1",children:"https://github.com/scalameta/metals/milestone/68?closed=1"})]}),"\n",(0,a.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs, Zed,\nHelix and Sublime Text. Metals is developed at the\n",(0,a.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,a.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from contributors from the community."]}),"\n",(0,a.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,a.jsxs)(t.p,{children:["Check out ",(0,a.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.a,{href:"#outline-compilation",children:"Outline compilation"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.a,{href:"#use-presentation-compiler-as-fallback-for-references-search",children:"Use presentation compiler as fallback for references search"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.a,{href:"#allow-to-override-debug-server-startup-timeout",children:"Allow to override debug server startup timeout"})}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"outline-compilation",children:"Outline compilation"}),"\n",(0,a.jsx)(t.p,{children:"Metals relies on information produced during compilation for much of its functionality. This limits the usefulness of many Metals features when the code is broken either by providing outdated information or no information if the project was never successfully compiled."}),"\n",(0,a.jsxs)(t.p,{children:["Starting with this release Metals will utilize ",(0,a.jsx)(t.a,{href:"https://github.com/scala/scala/blob/d800253bbd4cf90d2cf863f1b284dac3561e7446/src/compiler/scala/tools/nsc/settings/ScalaSettings.scala#L347",children:"outline compilation"})," to get current information about not compiling files for Scala 2.12.x and 2.13.x. This should improve the experience of working with not compiling code."]}),"\n",(0,a.jsx)(t.p,{children:"There are a few limitations to this improvement, the biggest being that the information from outline compilation will be only available within the same build target (module)."}),"\n",(0,a.jsx)(t.p,{children:"Further work is being done on a similar approach for Scala 3, which should be available in one of the upcoming releases."}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.img,{src:"https://i.imgur.com/ZBwWGTK.gif",alt:"broken-code"}),"\n",(0,a.jsx)(t.em,{children:"Completions on not compiling code"})]}),"\n",(0,a.jsx)(t.h2,{id:"use-presentation-compiler-as-fallback-for-references-search",children:"Use presentation compiler as fallback for references search"}),"\n",(0,a.jsx)(t.p,{children:"Before this release Metals would always use information from SemanticDB to search for references. Since SemanticDB is produced during compilation it would fail to find references for code that didn't compile. Now if SemanticDB information is missing or outdated Metals will fallback to using presentation compiler for reference search for all the files this concerns."}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.img,{src:"https://i.imgur.com/tI43rhl.gif",alt:"uncompiled-go-to-ref"}),"\n",(0,a.jsx)(t.em,{children:"Find references on non-compiling code"})]}),"\n",(0,a.jsx)(t.h2,{id:"allow-to-override-debug-server-startup-timeout",children:"Allow to override debug server startup timeout"}),"\n",(0,a.jsxs)(t.p,{children:["Until this release there was a fixed timeout for debug server startup time set to one minute. This proved to be insufficient for some larger projects and can now be overridden using a server property, e.g. ",(0,a.jsx)(t.code,{children:"-Dmetals.debug-server-start-timeout=90"})," increases the timeout to 90 seconds."]}),"\n",(0,a.jsx)(t.p,{children:"This is meant more as a temporary workaround, we also plan to work on decreasing the startup times, as this heavily impacts the user experience."}),"\n",(0,a.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["improvement: suggest scalafix config amend if ",(0,a.jsx)(t.code,{children:"OrganizeImports.targetDialect = Scala3"})," missing for Scala 3 ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Show actual Scala versions supported by Metals in docs and when running ",(0,a.jsx)(t.code,{children:"metals --versions"})," ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: Fix infinite indexing for broken sources ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: target jar classpath resolution for ",(0,a.jsx)(t.code,{children:"mill-bsp"})," ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: use OS specific spelling for Path/PATH ",(0,a.jsx)(t.a,{href:"https://github.com/mdelomba",children:"mdelomba"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: search for gradle wrapper at project root ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: don't start mtags with full classpath ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: indexing for filenames with ``` ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: infer correct build target for jars ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: fix millw script ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: insert missing members in correct place for case classes ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: highlighting and completions for multiline strings in worksheets in Scala 3 ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["feat: set unused tag for unused diagnostics ",(0,a.jsx)(t.a,{href:"https://github.com/ghostbuster91",children:"ghostbuster91"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: account for additional parenthesis around args in convert to named args ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: don't run additional compilation on find references ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: indexing when java files contain ",(0,a.jsx)(t.code,{children:"#include"})," header ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: correctly auto import when there is a renamed symbol with the same name in scope ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: handle implicit params in extract method ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: don't show implicit conversion for implicit params ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: resolve correctly project refs for sbt ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: make sure we always have correct projectview file for Bazel ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: convert block args to named args when in parenthesis ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: auto import for class names with long packages ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: don't create ",(0,a.jsx)(t.code,{children:"semanticdb"})," next to user sources for single files ",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"})]}),"\n",(0,a.jsxs)(t.li,{children:["fix: don't override ",(0,a.jsx)(t.code,{children:"JAVA_HOME"})," if it already exists for Bazel to avoid unnecessary restarts"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: recreate classloader if scalafix classloading fails. ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,a.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v1.3.1..v1.3.2\n    26\tKatarzyna Marek\n    24\tScalameta Bot\n    18\tTomasz Godzik\n     7\ttgodzik\n     4\tdependabot[bot]\n     1\tBohdan Buz\n     1\tBrian Wignall\n     1\tGrigorii Chudnov\n     1\tKasper Kondzielski\n     1\tMichael DeLomba\n     1\tscalameta-bot\n     1\tspamegg\n     1\ttemurlock\n"})}),"\n",(0,a.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,a.jsxs)(t.h2,{id:"v132-2024-06-19",children:[(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v1.3.2",children:"v1.3.2"})," (2024-06-19)"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v1.3.1...v1.3.2",children:"Full Changelog"})}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["improvement: Recreate classloader if scalafix classloading fails\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6516",children:"#6516"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Don't override JAVA_HOME if it already exists\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6504",children:"#6504"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: don't create ",(0,a.jsx)(t.code,{children:"semanticdb"})," next to user sources for single files\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6509",children:"#6509"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["refactor: code duplication in SupportedScalaVersions\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6511",children:"#6511"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/buzbohdan",children:"buzbohdan"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scalafmt-core from 3.8.1 to 3.8.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6514",children:"#6514"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scalameta, semanticdb-scalac, ... from 4.9.5 to 4.9.6\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6515",children:"#6515"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update xnio-nio from 3.8.15.Final to 3.8.16.Final\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6513",children:"#6513"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update ammonite-util from 3.0.0-M2-8-ba4429a2 to 3.0.0-M2-11-713b6963\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6512",children:"#6512"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Don't show bazel navigation issue always\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6510",children:"#6510"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: propagate jvmopts to tests\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6505",children:"#6505"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/gchudnov",children:"gchudnov"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: prefer latter fully qualified name parts when building trigrams for fuzzy search\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6482",children:"#6482"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: convert block args to named args when in parenthesis\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6487",children:"#6487"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add missing word\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6502",children:"#6502"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/bwignall",children:"bwignall"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Don't recalculate build tools every time\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6462",children:"#6462"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Make sure we always have correct projectview file\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6457",children:"#6457"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: sort abstract members for auto implement\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6496",children:"#6496"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: resolve correctly project refs for sbt\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6486",children:"#6486"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: don't delete classpath jars for running main from ",(0,a.jsx)(t.code,{children:".metals/.tmp"})," before server shutdown\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6495",children:"#6495"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: allow to override debug server startup timeout\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6492",children:"#6492"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: don't show implicit conversion for implicit params\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6493",children:"#6493"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Run mtags check parallel and set maximum timeout\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6451",children:"#6451"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update protobuf-java from 4.27.0 to 4.27.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6489",children:"#6489"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scribe, scribe-file, scribe-slf4j2 from 3.14.0 to 3.15.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6490",children:"#6490"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: use server command for doctor run in status bar\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6474",children:"#6474"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: handle implicit params in extract method\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6479",children:"#6479"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: correctly auto import when there is a renamed symbol with the same name in scope\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6480",children:"#6480"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: allow for ",(0,a.jsx)(t.code,{children:"#include"})," header\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6473",children:"#6473"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: don't run ",(0,a.jsx)(t.code,{children:"compileAndLookForNewReferences "}),"\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6429",children:"#6429"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Unignore and fix references Bazel test\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6458",children:"#6458"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["feat: Set unused tag for unused diagnostics\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6378",children:"#6378"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ghostbuster91",children:"ghostbuster91"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: Account for additional parenthesis around args in convert to named args\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6455",children:"#6455"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: batch pc references calls\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6453",children:"#6453"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scribe, scribe-file, scribe-slf4j2 from 3.13.5 to 3.14.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6471",children:"#6471"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update guava from 33.2.0-jre to 33.2.1-jre\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6469",children:"#6469"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update ammonite-util from 3.0.0-M2-6-38698450 to 3.0.0-M2-8-ba4429a2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6470",children:"#6470"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): bump @easyops-cn/docusaurus-search-local from 0.40.1 to 0.41.0 in /website\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6467",children:"#6467"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): bump @docusaurus/plugin-client-redirects from 3.2.1 to 3.4.0 in /website\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6466",children:"#6466"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): bump @docusaurus/core from 3.2.1 to 3.4.0 in /website\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6465",children:"#6465"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): bump scalacenter/sbt-dependency-submission from 2 to 3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6464",children:"#6464"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update requests from 0.8.2 to 0.8.3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6463",children:"#6463"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update faq.md\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6461",children:"#6461"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/spamegg1",children:"spamegg1"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Filter out target\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6460",children:"#6460"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: pc functions for multiline strings in worksheets in scala 3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6456",children:"#6456"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: insert missing members in correct place for case classes\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6454",children:"#6454"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["refactor: Don't try to download artifacts in ProblemResolverSuite\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6449",children:"#6449"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: do not cache presentation compilers for find references\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6448",children:"#6448"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: use pc for references when go to def on definition\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6447",children:"#6447"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Retry rename tests, since they seem most flaky\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6450",children:"#6450"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Use actual method arguments from DAP server interface\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6444",children:"#6444"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix millw script\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6446",children:"#6446"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update cli_3, scala-cli-bsp from 1.3.1 to 1.3.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6443",children:"#6443"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Infer correct build target for jars\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6437",children:"#6437"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update xnio-nio from 3.8.14.Final to 3.8.15.Final\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6442",children:"#6442"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update org.eclipse.lsp4j, ... from 0.22.0 to 0.23.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6434",children:"#6434"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: getting top levels for filenames with ```\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6430",children:"#6430"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update ammonite-util from 3.0.0-M2-5-1c823fef to 3.0.0-M2-6-38698450\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6441",children:"#6441"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Don't start mtags with full classpath\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6439",children:"#6439"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Bump Bazel BSP to a version with newest fixes\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6438",children:"#6438"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update org.eclipse.lsp4j, ... from 0.20.1 to 0.22.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6126",children:"#6126"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update munit from 1.0.0-RC1 to 1.0.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6435",children:"#6435"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update ammonite-util from 3.0.0-M2-3-b5eb4787 to 3.0.0-M2-5-1c823fef\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6433",children:"#6433"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update bloop-launcher-core from 1.5.17 to 1.5.18\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6431",children:"#6431"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update protobuf-java from 4.26.1 to 4.27.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6432",children:"#6432"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: look for gradle wrapper at project root\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6428",children:"#6428"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: use pc for finding references of local symbols and when semanticdb is missing\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5940",children:"#5940"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fixed issue where oldPath wasn't using OS specific spelling for Path/PATH\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6427",children:"#6427"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/mdelomba",children:"mdelomba"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Bump Scala Debug Adapter to latest\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6425",children:"#6425"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: ",(0,a.jsx)(t.code,{children:"targetJarClasspath"})," for ",(0,a.jsx)(t.code,{children:"mill-bsp"}),"\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6424",children:"#6424"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Automatically update bazel bsp\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6410",children:"#6410"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix infinite indexing\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6420",children:"#6420"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update ammonite-util from 3.0.0-M2-2-741e5dbb to 3.0.0-M2-3-b5eb4787\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6421",children:"#6421"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Outline compiler\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6114",children:"#6114"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["refactor: Move parts specific for workspace folder from ",(0,a.jsx)(t.code,{children:"MetalsLspServer"}),"\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6347",children:"#6347"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Bump Bazel BSP and add tests about warnings\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6407",children:"#6407"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Show actual Scala versions supported by Metals\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6417",children:"#6417"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: suggest scalafix config amend if `OrganizeImports.target\u2026\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6389",children:"#6389"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update ujson from 3.3.0 to 3.3.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6422",children:"#6422"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scalameta from 4.9.3 to 4.9.5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6423",children:"#6423"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["refactor: Add -Xsource:3 flag to easy migration to Scala 3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6411",children:"#6411"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update ammonite-util from 3.0.0-M1-24-26133e66 to 3.0.0-M2-2-741e5dbb\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6413",children:"#6413"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["remove unused bloomfilter\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6399",children:"#6399"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Temurlock",children:"Temurlock"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["docs: Add release notes for Metals 1.3.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/6403",children:"#6403"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,i.R)(),...e.components};return t?(0,a.jsx)(t,{...e,children:(0,a.jsx)(o,{...e})}):o(e)}},1020:(e,t,s)=>{var a=s(6540),i=Symbol.for("react.element"),l=Symbol.for("react.fragment"),r=Object.prototype.hasOwnProperty,n=a.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,h={key:!0,ref:!0,__self:!0,__source:!0};function c(e,t,s){var a,l={},c=null,o=null;for(a in void 0!==s&&(c=""+s),void 0!==t.key&&(c=""+t.key),void 0!==t.ref&&(o=t.ref),t)r.call(t,a)&&!h.hasOwnProperty(a)&&(l[a]=t[a]);if(e&&e.defaultProps)for(a in t=e.defaultProps)void 0===l[a]&&(l[a]=t[a]);return{$$typeof:i,type:e,key:c,ref:o,props:l,_owner:n.current}}t.Fragment=l,t.jsx=c,t.jsxs=c},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>r,x:()=>n});var a=s(6540);const i={},l=a.createContext(i);function r(e){const t=a.useContext(l);return a.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function n(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(i):e.components||i:r(e.components),a.createElement(l.Provider,{value:t},e.children)}}}]);