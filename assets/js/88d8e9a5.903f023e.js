/*! For license information please see 88d8e9a5.903f023e.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[767],{6539:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>h,contentTitle:()=>n,default:()=>d,frontMatter:()=>i,metadata:()=>r,toc:()=>c});var a=s(4848),l=s(8453);const i={author:"Vadim Chelyshov",title:"Metals v0.10.8 - Tungsten",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},n=void 0,r={permalink:"/metals/blog/2021/10/26/tungsten",source:"@site/blog/2021-10-26-tungsten.md",title:"Metals v0.10.8 - Tungsten",description:"We're happy to announce the release of Metals v0.10.8, which brings a lot of new features in addition to Scala 3.1.0 support!",date:"2021-10-26T00:00:00.000Z",tags:[],readingTime:7.19,hasTruncateMarker:!1,authors:[{name:"Vadim Chelyshov",url:"https://twitter.com/_dos65",imageURL:"https://github.com/dos65.png",key:null,page:null}],frontMatter:{author:"Vadim Chelyshov",title:"Metals v0.10.8 - Tungsten",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},unlisted:!1,prevItem:{title:"Metals v0.10.9 - Tungsten",permalink:"/metals/blog/2021/11/03/tungsten"},nextItem:{title:"Metals v0.10.7 - Tungsten",permalink:"/metals/blog/2021/09/16/tungsten"}},h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Expression Evaluator",id:"expression-evaluator",level:2},{value:"Type annotations on code selection",id:"type-annotations-on-code-selection",level:2},{value:"Source file analyzer",id:"source-file-analyzer",level:2},{value:"Find text in dependency JAR files",id:"find-text-in-dependency-jar-files",level:2},{value:"VSCode extension - workspace symbol search fix",id:"vscode-extension---workspace-symbol-search-fix",level:2},{value:"Scala 3.1.0 and completion improvements",id:"scala-310-and-completion-improvements",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.10.8 (2021-10-26)",id:"v0108-2021-10-26",level:2}];function o(e){const t={a:"a",code:"code",em:"em",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,l.R)(),...e.components};return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.10.8, which brings a lot of new features in addition to Scala 3.1.0 support!"}),"\n",(0,a.jsx)("table",{children:(0,a.jsxs)("tbody",{children:[(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Commits since last release"}),(0,a.jsx)("td",{align:"center",children:"162"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Merged PRs"}),(0,a.jsx)("td",{align:"center",children:"70"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Contributors"}),(0,a.jsx)("td",{align:"center",children:"16"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Closed issues"}),(0,a.jsx)("td",{align:"center",children:"11"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"New features"}),(0,a.jsx)("td",{align:"center",children:"4"})]})]})}),"\n",(0,a.jsxs)(t.p,{children:["For full details: ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/43?closed=1",children:"https://github.com/scalameta/metals/milestone/43?closed=1"})]}),"\n",(0,a.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text and Eclipse. Metals is developed at the\n",(0,a.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,a.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,a.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,a.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,a.jsxs)(t.p,{children:["Check out ",(0,a.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"Expression evaluator for Scala 2"}),"\n",(0,a.jsx)(t.li,{children:"[Scala 2] Type annotations on code selection"}),"\n",(0,a.jsx)(t.li,{children:"Source file analyzer"}),"\n",(0,a.jsx)(t.li,{children:"Find text in dependency JAR files"}),"\n",(0,a.jsx)(t.li,{children:"VSCode - workspace symbol search fix"}),"\n",(0,a.jsx)(t.li,{children:"Scala 3.1.0 and completion improvements"}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"expression-evaluator",children:"Expression Evaluator"}),"\n",(0,a.jsxs)(t.p,{children:["The long awaited ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/issues/3051",children:"issue #3051"})," was close to celebrating its 2 years birthday but\ndue to the impressive work by ",(0,a.jsx)(t.a,{href:"https://github.com/tdudzik",children:"@tdudzik"})," and help from ",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"@adapi"})," and\n",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"@tgodzik"})," Metals\nfinally has support for expression evaluation during debugging!\nThe implementation is based on the initial idea from ",(0,a.jsx)(t.a,{href:"https://github.com/smarter",children:"@smarter"}),"'s for expression evaluation in the Dotty Language Server."]}),"\n",(0,a.jsx)(t.p,{children:"Currently, it's implemented only for Scala 2, however the work on Scala3 support is in progress."}),"\n",(0,a.jsx)(t.p,{children:"Since expression evaluation is a complex feature there might still be some bugs that we didn't account for, so do not hesitate to report anything that doesn't look right."}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://i.imgur.com/1b17Vtm.gif",alt:"evaluator"})}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.em,{children:"Editors support"}),": every client that implements DAP"]}),"\n",(0,a.jsx)(t.h2,{id:"type-annotations-on-code-selection",children:"Type annotations on code selection"}),"\n",(0,a.jsxs)(t.p,{children:["Thanks to ",(0,a.jsx)(t.a,{href:"https://github.com/KacperFKorban",children:"@KacperFKorban"}),", yet another long awaited ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/issues/3059",children:"issue #3059"})," was closed.\nPreviously, hover in Metals was limited by the LSP protocol and only worked for a single position, however it turns out that it was possible to work around this limitation."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://user-images.githubusercontent.com/39772805/130333128-dc357170-116e-4a10-b58d-b55c536a2e15.gif",alt:"selection"})}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.em,{children:"Editors support"}),": VS Code, nvim-metals"]}),"\n",(0,a.jsx)(t.h2,{id:"source-file-analyzer",children:"Source file analyzer"}),"\n",(0,a.jsxs)(t.p,{children:["The ",(0,a.jsx)(t.code,{children:"Show Tasty command"})," from the previous release was extended.\nNow, Metals also can show javap and semanticdb outputs from the source.\nThanks ",(0,a.jsx)(t.a,{href:"https://github.com/Arthurm1",children:"@Arthurm1"})," for javap/semanticdb implementation and\n",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"@kpodsiad"})," for aligning all views into a single feature."]}),"\n",(0,a.jsx)(t.p,{children:"The full list of commands:"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.code,{children:"metals.show-tasty"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.code,{children:"metals.show-javap"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.code,{children:"metals.show-javap-verbose"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.code,{children:"metals.show-semanticdb-compact"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.code,{children:"metals.show-semanticdb-detailed"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.code,{children:"metals.show-semanticdb-proto"})}),"\n"]}),"\n",(0,a.jsxs)(t.p,{children:["In VS Code you can notice that there is a new ",(0,a.jsx)(t.code,{children:"Metals Analyze Source"})," submenu in file pop-up menu that provides a list of all these commands."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://i.imgur.com/6tGSvuI.gif",alt:"analyze"})}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.em,{children:"Editors support"}),": VS Code, nvim-metals"]}),"\n",(0,a.jsx)(t.h2,{id:"find-text-in-dependency-jar-files",children:"Find text in dependency JAR files"}),"\n",(0,a.jsxs)(t.p,{children:["This release also introduces another helpful feature, it's possible now to do text search through files in classpath jars and source-jars.\nThanks to ",(0,a.jsx)(t.a,{href:"https://github.com/Z1kkurat",children:"@ Z1kkurat"})," for his contribution!"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://i.imgur.com/o1Drd12.gif",alt:"find-in-jars"})}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.em,{children:"Editors support"}),": VS Code, nvim-metals"]}),"\n",(0,a.jsx)(t.h2,{id:"vscode-extension---workspace-symbol-search-fix",children:"VSCode extension - workspace symbol search fix"}),"\n",(0,a.jsx)(t.p,{children:"Initially, workspace symbol search in Metals allowed you to extend the search in a couple of ways:"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["\n",(0,a.jsxs)(t.p,{children:["It was possible to look through all the dependencies by adding the semicolon ",(0,a.jsx)(t.code,{children:";"})," at the end or to search. This was done by default if no results were found in the current workspace."]}),"\n"]}),"\n",(0,a.jsxs)(t.li,{children:["\n",(0,a.jsxs)(t.p,{children:["Users could use the full path for symbols such as ",(0,a.jsx)(t.code,{children:"j.n.f.Path"})," for ",(0,a.jsx)(t.code,{children:"java.nio.file.Path"})," ."]}),"\n"]}),"\n"]}),"\n",(0,a.jsxs)(t.p,{children:["Unfortunately, this stopped being possible in some later Visual Studio Code versions. To work around that issue a new command was added: ",(0,a.jsx)(t.code,{children:"metals.symbol-search"}),", which allows for the additional search features. This already works for all other editors.\nIn addition, Metals extension now overrides default keybing for symbol search: ",(0,a.jsx)(t.code,{children:"Ctrl+T"}),"/",(0,a.jsx)(t.code,{children:"Cmd+T"})," executes this Metals command."]}),"\n",(0,a.jsx)(t.p,{children:"An important notice: we can't fix the default workspace symbol search. It still uses default VS Code implementation."}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://user-images.githubusercontent.com/5816952/133675550-c199e79a-55cc-4df6-871d-c8a3b1f0b3a3.gif",alt:"symbol-search"})}),"\n",(0,a.jsx)(t.h2,{id:"scala-310-and-completion-improvements",children:"Scala 3.1.0 and completion improvements"}),"\n",(0,a.jsxs)(t.p,{children:["And lastly this new Metals release comes with a new compiler version support - 3.1.0. As well as support for the next release candidate ",(0,a.jsx)(t.code,{children:"3.1.1-RC1"}),", which will be later updated in the SNAPSHOT Metals versions in case of further release candidates.\nWe also further improved the support for Scala 3:"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"Completion items ordering were reworked and now it should match the behaviour of Scala 2"}),"\n",(0,a.jsx)(t.li,{children:"Type descriptions in completion now shows the precise type for generic methods"}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["Fix type decorations for sbt/standalone files ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"@tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["Use Scala 3 syntax by default. ",(0,a.jsx)(t.a,{href:"https://github.com/smarter",children:"@smarter"})]}),"\n",(0,a.jsxs)(t.li,{children:["Support more scenarios in rewrite parent/braces code action. ",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"@kpodsiad"})]}),"\n",(0,a.jsxs)(t.li,{children:["Fix go to parent code lenses for local symbols. ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["Strip out [info] for stacktraces. ",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"})]}),"\n",(0,a.jsxs)(t.li,{children:["[sbt server] Fix meta-build-target configuration. ",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"})]}),"\n",(0,a.jsxs)(t.li,{children:["Add build server version to the doctor view. ",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"})]}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,a.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.10.7..v0.10.8\n25\tTomasz Godzik\n20\tVadim Chelyshov\n13\tZ1kkurat\n10\tAdrien Piquerez\n8\tKamil Podsiadlo\n4\tckipp01\n3\tGabriele Petronella\n2\ttdudzik\n2\t\xd3lafur P\xe1ll Geirsson\n1\tAlexandre Archambault\n1\tArthur McGibbon\n1\tGuillaume Martres\n1\tKacper Korban\n"})}),"\n",(0,a.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,a.jsxs)(t.h2,{id:"v0108-2021-10-26",children:[(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.10.8",children:"v0.10.8"})," (2021-10-26)"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.10.7...v0.10.8",children:"Full Changelog"})}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["Compilers: try to fix NPE\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3227",children:"#3227"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Print exact type for expressions containing generics\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3223",children:"#3223"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update debug adapter to 2.0.8\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3226",children:"#3226"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tdudzik",children:"tdudzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Always show hover on a non empty range selection\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3222",children:"#3222"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Reset diagnostics on build import\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3220",children:"#3220"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Z1kkurat",children:"Z1kkurat"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add Scala 3.1.0 support\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3212",children:"#3212"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update Bloop to 1.4.10\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3218",children:"#3218"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump debug adapter\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3216",children:"#3216"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Completions - show applied type for ",(0,a.jsx)(t.code,{children:"Select"}),"\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3188",children:"#3188"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Allow to generate run lenses for bsp servers which have debug capability\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3210",children:"#3210"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use sbt-debug-adapter explicity in metals.sbt\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3211",children:"#3211"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use SemanticdbPlugin in sbt-metals\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3200",children:"#3200"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Workflows: ignore sourcegraph upload error\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3208",children:"#3208"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["EndpointLogger: close trace writer\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3207",children:"#3207"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump Bloop and scala-debug-adapter\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3209",children:"#3209"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scribe, scribe-file, scribe-slf4j to 3.6.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3205",children:"#3205"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 8.0.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3206",children:"#3206"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update ujson to 1.4.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3204",children:"#3204"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-debug-adapter to 2.0.5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3203",children:"#3203"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Find in jar minor fixes\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3198",children:"#3198"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix flaky cancel compile suite\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3202",children:"#3202"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add new suites to TestGroups\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3199",children:"#3199"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[sbt server] Fix meta-build-target configuration\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3194",children:"#3194"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Decode file refactor\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3160",children:"#3160"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't use ",(0,a.jsx)(t.code,{children:"window/showMessage"})," to report generic Scalafix error\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3192",children:"#3192"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Wait with loading compiler and compilation\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3190",children:"#3190"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Implement 'find in JAR files' LSP extension\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3093",children:"#3093"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Z1kkurat",children:"Z1kkurat"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't throw an exception is compiling semanticdb times out\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3187",children:"#3187"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Wait for build tool information when generating semanticdb\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3184",children:"#3184"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump bloop to fix some debug issues\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3185",children:"#3185"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Remove unused ",(0,a.jsx)(t.code,{children:"bspEnabled := false"}),"\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3186",children:"#3186"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Updage scalafmt + use optional braces syntax for scala3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3165",children:"#3165"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't create file system when debugging\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3183",children:"#3183"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Remove extra boolean from the ",(0,a.jsx)(t.code,{children:"goto-position"})," server command in ",(0,a.jsx)(t.code,{children:"StacktraceAnalyzer"}),"\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3179",children:"#3179"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Upgrade scala3 rc version\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3182",children:"#3182"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Strip out [info] for stacktraces.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3180",children:"#3180"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Avoid IllegalArgumentException when trying to get type of range.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3178",children:"#3178"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Make sure generate-sources phase is always run for Maven\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3164",children:"#3164"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scala-java8-compat to 1.0.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3175",children:"#3175"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Try to fix flaky tests\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3163",children:"#3163"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalafmt-dynamic to 3.0.5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3177",children:"#3177"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update jsoup to 1.14.3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3174",children:"#3174"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 7.15.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3173",children:"#3173"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update undertow-core to 2.2.12.Final\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3172",children:"#3172"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update guava to 31.0.1-jre\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3171",children:"#3171"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update metaconfig-core to 0.9.15\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3170",children:"#3170"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update jackson-databind to 2.13.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3169",children:"#3169"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add completion support to expression evaluator\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3159",children:"#3159"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Reconnect to BSP server upon buildTarget/didChange\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3145",children:"#3145"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/alexarchambault",children:"alexarchambault"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't search for all symbols in go-to-parent code lenses\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3161",children:"#3161"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump Bloop and sbt-debug-adapter\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3166",children:"#3166"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Create dependency files on step if they don't exist\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3167",children:"#3167"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix go to parent code lenses for local symbols\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3154",children:"#3154"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't close source jars\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3162",children:"#3162"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add position to show tasty command\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3148",children:"#3148"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use scala-debug-adapter 2.x to enable code evaluation\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2959",children:"#2959"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Type annotation on code selection\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3060",children:"#3060"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/KacperFKorban",children:"KacperFKorban"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Make commands parametrized and easier to use\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3149",children:"#3149"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Generate symbol information for standalone files, worksheets and sbt files\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3019",children:"#3019"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update Sourcegraph workflow to use ",(0,a.jsx)(t.code,{children:"lsif-java index"})," command.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3152",children:"#3152"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Only set sbt dialect when file has indeed .sbt extension\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3144",children:"#3144"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Support more scenarios with 'replace () with ","' code action\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3130",children:"#3130"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["add javap/semanticdb file viewer\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3107",children:"#3107"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Arthurm1",children:"Arthurm1"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add build server version to the doctor view\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3141",children:"#3141"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalafix to 0.9.31\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3138",children:"#3138"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add docs for Metals custom search command\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3139",children:"#3139"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Improve completions ordering\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3115",children:"#3115"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Set the default fallbackScalaVersion to scala 3 instead of 2.12\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3134",children:"#3134"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/smarter",children:"smarter"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump Scalafmt version\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3137",children:"#3137"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add release notes for Metals v0.10.7\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3132",children:"#3132"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,l.R)(),...e.components};return t?(0,a.jsx)(t,{...e,children:(0,a.jsx)(o,{...e})}):o(e)}},1020:(e,t,s)=>{var a=s(6540),l=Symbol.for("react.element"),i=Symbol.for("react.fragment"),n=Object.prototype.hasOwnProperty,r=a.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,h={key:!0,ref:!0,__self:!0,__source:!0};function c(e,t,s){var a,i={},c=null,o=null;for(a in void 0!==s&&(c=""+s),void 0!==t.key&&(c=""+t.key),void 0!==t.ref&&(o=t.ref),t)n.call(t,a)&&!h.hasOwnProperty(a)&&(i[a]=t[a]);if(e&&e.defaultProps)for(a in t=e.defaultProps)void 0===i[a]&&(i[a]=t[a]);return{$$typeof:l,type:e,key:c,ref:o,props:i,_owner:r.current}}t.Fragment=i,t.jsx=c,t.jsxs=c},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>n,x:()=>r});var a=s(6540);const l={},i=a.createContext(l);function n(e){const t=a.useContext(i);return a.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:n(e.components),a.createElement(i.Provider,{value:t},e.children)}}}]);