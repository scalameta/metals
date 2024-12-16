"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["1944"],{16643:function(e,t,s){s.r(t),s.d(t,{assets:function(){return r},contentTitle:function(){return h},default:function(){return d},frontMatter:function(){return l},metadata:function(){return a},toc:function(){return c}});var a=s(61617),n=s(85893),i=s(50065);let l={authors:"tgodzik",title:"Metals v0.10.6 - Tungsten"},h=void 0,r={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Automatically adjust indentation on paste",id:"automatically-adjust-indentation-on-paste",level:2},{value:"New code actions",id:"new-code-actions",level:2},{value:"Refactor pattern match with placeholder",id:"refactor-pattern-match-with-placeholder",level:3},{value:"Replace () with  in functions and vice versa",id:"replace--with--in-functions-and-vice-versa",level:3},{value:"Allow organize imports on unsaved code",id:"allow-organize-imports-on-unsaved-code",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.10.6 (2021-09-06)",id:"v0106-2021-09-06",level:2}];function o(e){let t={a:"a",code:"code",h2:"h2",h3:"h3",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,i.a)(),...e.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.10.6, which brings about new\nScala 3 versions and JDK 17 support in addition to some new interesting\nfeatures."}),"\n",(0,n.jsx)("table",{children:(0,n.jsxs)("tbody",{children:[(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Commits since last release"}),(0,n.jsx)("td",{align:"center",children:"173"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Merged PRs"}),(0,n.jsx)("td",{align:"center",children:"74"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Contributors"}),(0,n.jsx)("td",{align:"center",children:"16"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Closed issues"}),(0,n.jsx)("td",{align:"center",children:"25"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"New features"}),(0,n.jsx)("td",{align:"center",children:"4"})]})]})}),"\n",(0,n.jsxs)(t.p,{children:["For full details: ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/41?closed=1",children:"https://github.com/scalameta/metals/milestone/41?closed=1"})]}),"\n",(0,n.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text and Eclipse. Metals is developed at the\n",(0,n.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,n.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,n.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,n.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,n.jsxs)(t.p,{children:["Check out ",(0,n.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"Automatically adjust indentation on paste."}),"\n",(0,n.jsx)(t.li,{children:"New code actions."}),"\n",(0,n.jsx)(t.li,{children:"Allow organize imports on unsaved code."}),"\n",(0,n.jsx)(t.li,{children:"[Scala 3] support for Scala 3.0.2 and 3.1.0-RC1."}),"\n",(0,n.jsx)(t.li,{children:"JDK 17 support."}),"\n"]}),"\n",(0,n.jsx)(t.h2,{id:"automatically-adjust-indentation-on-paste",children:"Automatically adjust indentation on paste"}),"\n",(0,n.jsx)(t.p,{children:"Starting with Scala 3 developers can use optional braces, which we mentioned in\nsome of the previous release notes. One of the main problems with it is making\nsure that users are able to properly paste code into existing blocks. Previously\nwe could just paste into braced blocks and adjusted the indentation later, but\nnow it really matters that indentation is changed properly in order to make to\ncode compile correctly."}),"\n",(0,n.jsxs)(t.p,{children:["To help with that ",(0,n.jsx)(t.a,{href:"https://github.com/Giggiux",children:"@Giggiux"})," adjusted the existing\nrange formatting to account for indentation in the pasted blocks. Starting from\nthis release Metals will adjust the indentation to match that of the enclosing\nblock irrelevant of whether it has braces or not. A number of improvements has\nalso been done to make sure the cursor ends up on the correct indentation level\nupon writing a newline."]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/TVewmY7.gif",alt:"indentation"})}),"\n",(0,n.jsxs)(t.p,{children:["If you encounter any issues, please don't hesitate to report them since the\nfunctionality is new and there might be some edge cases where it doesn't work.\nThis was tested internally over the past month, but that doesn't account for all\nthe different styles of how people write their code. You can turn off on paste\nformatting in Visual Studio Code in the settings using the\n",(0,n.jsx)(t.code,{children:"editor.formatOnPaste"})," property. This feature will only work with the editors\nthat support range formatting."]}),"\n",(0,n.jsx)(t.h2,{id:"new-code-actions",children:"New code actions"}),"\n",(0,n.jsxs)(t.p,{children:["This release benefits from adding two new code actions. Actions are based on\nparticular scenarios, where we might automate some mundane coding and their\nbehaviour is usually clearly specified. Please let us know if there are any\nother particular situations, which might be automated, via the the Metals's\n",(0,n.jsx)(t.a,{href:"http://github.com/metals-feature-requests",children:"feature requests repository"}),"."]}),"\n",(0,n.jsx)(t.h3,{id:"refactor-pattern-match-with-placeholder",children:"Refactor pattern match with placeholder"}),"\n",(0,n.jsxs)(t.p,{children:["Thanks to ",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"})," it's now possible to\nautomatically rewrite lambdas with ",(0,n.jsx)(t.code,{children:"_ match"})," expressions to partial functions:"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/Ffm4RXZ.gif",alt:"partial-func"})}),"\n",(0,n.jsxs)(t.h3,{id:"replace--with--in-functions-and-vice-versa",children:["Replace () with "," in functions and vice versa"]}),"\n",(0,n.jsx)(t.p,{children:"It's quite often that in Scala we might want to change to a simple lambda to a\nmultiline one or vice versa. This has previously been a manual chore, but now\nwhenever possible you can do it via a code action:"}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/DTylGT9.gif",alt:"replace"})}),"\n",(0,n.jsx)(t.h2,{id:"allow-organize-imports-on-unsaved-code",children:"Allow organize imports on unsaved code"}),"\n",(0,n.jsx)(t.p,{children:"In previous version it was only possible to organize imports in files that were\nsaved and compiled. We're happy to report that this requirement has been lifted\nand users can organize imports whenever needed."}),"\n",(0,n.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"Fix completions for definitions from other files in Ammonite scripts."}),"\n",(0,n.jsxs)(t.li,{children:["Support for Apple M1 and JDK 17. ",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"@dos65"})]}),"\n",(0,n.jsx)(t.li,{children:"[Scala 3] Show extension methods in hover."}),"\n",(0,n.jsxs)(t.li,{children:["Support brackets in multiline strings.\n",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"@kpodsiad"})]}),"\n",(0,n.jsx)(t.li,{children:"Automatically convert $ to $$ when changing to string interpolation."}),"\n",(0,n.jsxs)(t.li,{children:["Filter out wrong reference results from unrelated build targets.\n",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"@dos65"})]}),"\n",(0,n.jsxs)(t.li,{children:["Greatly improved parent code lenses performance and turned it on by default in\nVS Code. ",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"@tanishiking"})]}),"\n",(0,n.jsxs)(t.li,{children:["Properly show folding ranges in Scala 3 indented blocks.\n",(0,n.jsx)(t.a,{href:"https://github.com/tpasternak",children:"@tpasternak"})]}),"\n",(0,n.jsx)(t.li,{children:"Fix comments erasing all later variables in .env file."}),"\n",(0,n.jsxs)(t.li,{children:["Fix file watcher in large projects on macOS. ",(0,n.jsx)(t.a,{href:"https://github.com/pvid",children:"@pvid"})]}),"\n",(0,n.jsxs)(t.li,{children:["Fix memory leak issue in Scala 3 projects. ",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"@dos65"})]}),"\n"]}),"\n",(0,n.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,n.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,n.jsx)(t.pre,{children:(0,n.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.10.5..v0.10.6\nTomasz Godzik\nLuigi Frunzio\nVadim Chelyshov\nAdrien Piquerez\nChris Kipp\nPavol Vidlicka\nGabriele Petronella\nKamil Podsiadlo\nOliver Schrenk\n\xd3lafur P\xe1ll Geirsson\nRikito Taniguchi\nTomasz Pasternak\n"})}),"\n",(0,n.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,n.jsxs)(t.h2,{id:"v0106-2021-09-06",children:[(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.10.6",children:"v0.10.6"})," (2021-09-06)"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.10.5...v0.10.6",children:"Full Changelog"})}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsxs)(t.li,{children:["Fix issue with tmp directory popping up in random places\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3105",children:"#3105"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Ident on paste - bunch of fixes\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3080",children:"#3080"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["s/horizontal/vertical ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3102",children:"#3102"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/oschrenk",children:"oschrenk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["s/defalt/default ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3101",children:"#3101"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/oschrenk",children:"oschrenk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add support for Scala 3.1.0-RC1\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3100",children:"#3100"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add support for Scala 3.0.2\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3095",children:"#3095"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update mdoc-interfaces to 2.2.23\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3094",children:"#3094"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update munit to 0.7.29 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3088",children:"#3088"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalafix-interfaces to 0.9.30\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3089",children:"#3089"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update jsoup to 1.14.2 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3086",children:"#3086"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update file-tree-views to 2.1.7\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3084",children:"#3084"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Bump olafurpg/setup-scala from 12 to 13\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3090",children:"#3090"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update mdoc, mdoc-interfaces, sbt-mdoc to 2.2.23\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3087",children:"#3087"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update flyway-core to 7.14.0\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3085",children:"#3085"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update jackson-databind to 2.12.5\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3083",children:"#3083"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update sbt-scalafix, scalafix-interfaces to 0.9.30\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3082",children:"#3082"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Run tests on JDK 17 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3028",children:"#3028"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix file watcher in large projects on macOS\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3021",children:"#3021"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/pvid",children:"pvid"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Allow organize imports on unsaved code\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3055",children:"#3055"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update Bloop to the newest nightly\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3003",children:"#3003"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Jar indexing - fix issue with empty jar source\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3069",children:"#3069"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix comments erasing all later variables in .env file\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3070",children:"#3070"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update next Scala 3 RC version to 3.0.2-RC2\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3065",children:"#3065"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add action to replace () with "," in functions and vice versa\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3061",children:"#3061"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Handle Scala 3 style blocks of code without braces\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3058",children:"#3058"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tpasternak",children:"tpasternak"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add tests explicitly to tests shard\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3049",children:"#3049"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Remove complicated asSeenFrom logic\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3047",children:"#3047"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add documentation about GitHub Codespaces and GitHub.dev support\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3046",children:"#3046"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Simplify super method lens using overriddenSymbols\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3045",children:"#3045"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalafmt to 3.0.0-RC7\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3042",children:"#3042"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["[Scala3] Fix memory usage spike on empty completions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3043",children:"#3043"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update sbt-sourcegraph to 0.3.3\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3035",children:"#3035"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalameta, semanticdb-scalac, ... to 4.4.27\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3041",children:"#3041"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalafmt-core to 3.0.0-RC7\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3039",children:"#3039"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update munit to 0.7.28 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3038",children:"#3038"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update flyway-core to 7.13.0\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3037",children:"#3037"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update undertow-core to 2.2.10.Final\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3036",children:"#3036"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Alternative version of #2999: Add more tests on using sbt as the build server\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3006",children:"#3006"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalameta to 4.4.26\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3027",children:"#3027"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix indentation when pasting part of the line\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3025",children:"#3025"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["References - filter out wrong results from unrelated build targets\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2980",children:"#2980"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Upgrade to the latest sbt-sourcegraph\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3022",children:"#3022"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Merge sourcegraph branch into main.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3017",children:"#3017"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Make the SemanticDB compiler options used by mtags configurable.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3018",children:"#3018"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Minimize file rewrites in ",(0,n.jsx)(t.code,{children:".metals/readonly/dependencies"}),"\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3004",children:"#3004"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Automatic refactor - pattern match with placeholder\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3002",children:"#3002"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Bump coursier/cache-action from 6.2 to 6.3\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3015",children:"#3015"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update mdoc, sbt-mdoc to 2.2.22\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3013",children:"#3013"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update flyway-core to 7.11.4\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3012",children:"#3012"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update undertow-core to 2.2.9.Final\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3011",children:"#3011"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update mill-contrib-testng to 0.9.9\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/3010",children:"#3010"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Automatically convert $ to $$ when changing to string interpolation\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2996",children:"#2996"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Support brackets in multiline strings\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2995",children:"#2995"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["[Scala3] Additional completion pos fix for ",(0,n.jsx)(t.code,{children:"Select"})," tree\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2998",children:"#2998"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Mention new run/test commands in the VS Code editor page\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2993",children:"#2993"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Point to coc.nvim for defaults rather than have them in here.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2988",children:"#2988"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Include parens on lambda in Scala 3 when inserting type in params with brace.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2989",children:"#2989"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Correctly paste extension groups\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2987",children:"#2987"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["[Scala 3] Support extension methods in hover\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2978",children:"#2978"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Intend on paste ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2815",children:"#2815"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/Giggiux",children:"Giggiux"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update default version for bug report\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2984",children:"#2984"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update mdoc to 2.2.22 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2981",children:"#2981"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["[Scala 3] Filter out -print-lines and -print-tasty options\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2979",children:"#2979"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Replace the usage of nuprocess\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2933",children:"#2933"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Mtags3 - exclude scala2 depedencies.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2971",children:"#2971"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix ammonite type completions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2974",children:"#2974"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update munit to 0.7.27 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2967",children:"#2967"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update jsoup to 1.14.1 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2965",children:"#2965"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update flyway-core to 7.11.2\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2964",children:"#2964"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Bump cache-action to latest.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2970",children:"#2970"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update jackson-databind to 2.12.4\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2963",children:"#2963"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalafmt-core to 3.0.0-RC6\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2968",children:"#2968"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Upgrade snapshot version\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2957",children:"#2957"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix release notes date ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2958",children:"#2958"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["0.10.5 - release notes ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2953",children:"#2953"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,i.a)(),...e.components};return t?(0,n.jsx)(t,{...e,children:(0,n.jsx)(o,{...e})}):o(e)}},50065:function(e,t,s){s.d(t,{Z:function(){return h},a:function(){return l}});var a=s(67294);let n={},i=a.createContext(n);function l(e){let t=a.useContext(i);return a.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function h(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:l(e.components),a.createElement(i.Provider,{value:t},e.children)}},61617:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2021/09/06/tungsten","source":"@site/blog/2021-09-06-tungsten.md","title":"Metals v0.10.6 - Tungsten","description":"We\'re happy to announce the release of Metals v0.10.6, which brings about new","date":"2021-09-06T00:00:00.000Z","tags":[],"readingTime":6.89,"hasTruncateMarker":false,"authors":[{"name":"Tomasz Godzik","url":"https://twitter.com/TomekGodzik","imageURL":"https://github.com/tgodzik.png","key":"tgodzik","page":null}],"frontMatter":{"authors":"tgodzik","title":"Metals v0.10.6 - Tungsten"},"unlisted":false,"prevItem":{"title":"Metals v0.10.7 - Tungsten","permalink":"/metals/blog/2021/09/16/tungsten"},"nextItem":{"title":"Metals v0.10.5 - Tungsten","permalink":"/metals/blog/2021/07/14/tungsten"}}')}}]);