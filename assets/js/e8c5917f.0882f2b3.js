"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["3665"],{4573:function(e,t,s){s.r(t),s.d(t,{assets:function(){return r},contentTitle:function(){return h},default:function(){return d},frontMatter:function(){return l},metadata:function(){return i},toc:function(){return c}});var i=s(8495),n=s(5893),a=s(65);let l={authors:"tanishiking",title:"Metals v0.11.8 - Aluminium"},h=void 0,r={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"[Scala 3] Auto import and completion for extension methods",id:"scala-3-auto-import-and-completion-for-extension-methods",level:2},{value:"[Scala 3] Convert to Named Parameters code action",id:"scala-3-convert-to-named-parameters-code-action",level:2},{value:"[Scala 3] Scaladoc completion",id:"scala-3-scaladoc-completion",level:2},{value:"[Scala 3] Completions in string interpolation",id:"scala-3-completions-in-string-interpolation",level:2},{value:"[Scala 2] Automatically import types in string interpolations",id:"scala-2-automatically-import-types-in-string-interpolations",level:2},{value:"Code Action documentation",id:"code-action-documentation",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.11.8 (2022-08-10)",id:"v0118-2022-08-10",level:2}];function o(e){let t={a:"a",code:"code",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,a.a)(),...e.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.11.8, bringing a number of\nimprovements for both Scala 2 and Scala 3."}),"\n",(0,n.jsx)("table",{children:(0,n.jsxs)("tbody",{children:[(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Commits since last release"}),(0,n.jsx)("td",{align:"center",children:"84"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Merged PRs"}),(0,n.jsx)("td",{align:"center",children:"80"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Contributors"}),(0,n.jsx)("td",{align:"center",children:"15"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Closed issues"}),(0,n.jsx)("td",{align:"center",children:"22"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"New features"}),(0,n.jsx)("td",{align:"center",children:"5"})]})]})}),"\n",(0,n.jsxs)(t.p,{children:["For full details: ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/52?closed=1",children:"https://github.com/scalameta/metals/milestone/52?closed=1"})]}),"\n",(0,n.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs and\nSublime Text. Metals is developed at the ",(0,n.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"}),"\nand ",(0,n.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"})," with the help from\n",(0,n.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from the community."]}),"\n",(0,n.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,n.jsxs)(t.p,{children:["Check out ",(0,n.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"[Scala 3] Auto import and completion for extension methods"}),"\n",(0,n.jsx)(t.li,{children:"[Scala 3] Convert to Named Parameters code action"}),"\n",(0,n.jsx)(t.li,{children:"[Scala 3] Scaladoc Completion for Scala3"}),"\n",(0,n.jsx)(t.li,{children:"[Scala 3] Completions in string interpolation"}),"\n",(0,n.jsx)(t.li,{children:"[Scala 2] Automatic import of types in string interpolations"}),"\n",(0,n.jsx)(t.li,{children:"Code Action documentation"}),"\n",(0,n.jsx)(t.li,{children:"Support of Scala 3.2.0-RC3, Scala 3.2.0-RC2"}),"\n"]}),"\n",(0,n.jsx)(t.p,{children:"and a lot of bugfixes!"}),"\n",(0,n.jsx)(t.h2,{id:"scala-3-auto-import-and-completion-for-extension-methods",children:"[Scala 3] Auto import and completion for extension methods"}),"\n",(0,n.jsxs)(t.p,{children:["You might know that Scala 3 has introduced ",(0,n.jsx)(t.code,{children:"extension methods"})," that allow\ndefining new methods to your existing types."]}),"\n",(0,n.jsx)(t.p,{children:"Previously, Metals couldn't auto-complete extension methods; so developers had\nto find an appropriate extension method from their workspace and manually import\nit. But, this was time-consuming and not always beginner friendly."}),"\n",(0,n.jsx)(t.p,{children:"Now, Metals provides auto-completion for extension methods and automatically\nimports them!"}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/EAbVHeH.gif",alt:"extension-methods"})}),"\n",(0,n.jsx)(t.h2,{id:"scala-3-convert-to-named-parameters-code-action",children:"[Scala 3] Convert to Named Parameters code action"}),"\n",(0,n.jsxs)(t.p,{children:[(0,n.jsxs)(t.a,{href:"https://scalameta.org/metals/blog/2022/07/04/aluminium#scala-2-add-converttonamedarguments-code-action",children:["Metals 0.11.7 added ",(0,n.jsx)(t.code,{children:"ConvertToNamedParameters"})," code action to Scala2"]}),"."]}),"\n",(0,n.jsxs)(t.p,{children:["Thanks to the contribution by ",(0,n.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"@jkciesluk"}),", this\nfeature is now available for Scala 3!"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/9i7MWoQ.gif",alt:"convert-to-named"})}),"\n",(0,n.jsx)(t.h2,{id:"scala-3-scaladoc-completion",children:"[Scala 3] Scaladoc completion"}),"\n",(0,n.jsxs)(t.p,{children:["Metals now supports offering Scaladoc completions in Scala 3. When typing ",(0,n.jsx)(t.code,{children:"/**"}),"\nyou get an option to auto-complete a scaladoc template for methods, classes,\netc.!"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/MEJUXr3.gif",alt:"scala-doc-completion"})}),"\n",(0,n.jsx)(t.h2,{id:"scala-3-completions-in-string-interpolation",children:"[Scala 3] Completions in string interpolation"}),"\n",(0,n.jsxs)(t.p,{children:["In the previous versions, whenever users wanted to include a value in a string\nusing string interpolation, they would need to do it all manually. Now, it is\npossible to get an automatic conversion to string interpolation when typing\n",(0,n.jsx)(t.code,{children:"$value"}),", as well as automatic wrapping in ",(0,n.jsx)(t.code,{children:"{}"})," when accessing members of such\nvalue."]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/EyFKpiv.gif",alt:"scala3-interpolation"})}),"\n",(0,n.jsx)(t.h2,{id:"scala-2-automatically-import-types-in-string-interpolations",children:"[Scala 2] Automatically import types in string interpolations"}),"\n",(0,n.jsx)(t.p,{children:"Previously, the only suggestions for string interpolations were coming from the\ncurrently available symbols in scope. This meant that if you wanted to import\nsomething from another package, you would need to do it manually."}),"\n",(0,n.jsx)(t.p,{children:"This problem is now resolved. Users can easily get such symbols automatically\nimported, which creates a seamless workflow."}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:"https://i.imgur.com/cCWTQnj.gif",alt:"scala2-inteprolation"})}),"\n",(0,n.jsx)(t.p,{children:"The feature is also being worked on for Scala 3."}),"\n",(0,n.jsx)(t.h2,{id:"code-action-documentation",children:"Code Action documentation"}),"\n",(0,n.jsxs)(t.p,{children:["Have you ever wondered what kind of refactorings are available in Metals? Check\nout this new page in the documentation! You can see a list of all the code\nactions in Metals with examples.\n",(0,n.jsx)(t.a,{href:"https://scalameta.org/metals/docs/codeactions/codeactions",children:"https://scalameta.org/metals/docs/codeactions/codeactions"})]}),"\n",(0,n.jsxs)(t.p,{children:["Big thanks to ",(0,n.jsx)(t.a,{href:"https://github.com/vzmerr",children:"zmerr"})," for writing this documentation."]}),"\n",(0,n.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,n.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,n.jsx)(t.pre,{children:(0,n.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.11.7..v0.11.8\n33	Tomasz Godzik\n    11	Rikito Taniguchi\n     9	Scala Steward\n     6	jkciesluk\n     6	Kamil Podsiad\u0142o\n     5	vzmerr\n     3	Vadim Chelyshov\n     2	Adrien Piquerez\n     2	scalameta-bot\n     2	zmerr\n     1	Arthur S\n     1	Anton Sviridov\n     1	tgodzik\n     1	Scalameta Bot\n     1	Chris Kipp\n"})}),"\n",(0,n.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,n.jsxs)(t.h2,{id:"v0118-2022-08-10",children:[(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.11.8",children:"v0.11.8"})," (2022-08-10)"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.11.7...v0.11.8",children:"Full Changelog"})}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsxs)(t.li,{children:["[Scala 3] Revert type completions feature\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4236",children:"#4236"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Show package completions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4223",children:"#4223"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore(ci): small changes to account for migration from LSIF -> SCIP\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4222",children:"#4222"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: Switch to JDK 17 for most tests\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4219",children:"#4219"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Print correct method signature for Selectable\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4202",children:"#4202"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Scala 3 type completion\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4174",children:"#4174"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/vzmerr",children:"vzmerr"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Don't use interrupt for the Scala 3 compiler\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4200",children:"#4200"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update scalameta, semanticdb-scalac, ... from 4.5.9 to 4.5.11\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4210",children:"#4210"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feat: Auto complete (missing) extension methods\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4183",children:"#4183"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update mdoc, mdoc-interfaces, sbt-mdoc from 2.3.2 to 2.3.3\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4209",children:"#4209"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update flyway-core from 9.0.1 to 9.0.4\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4208",children:"#4208"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: Bump Bloop to latest to test out recent changes\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4204",children:"#4204"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update debug adapter to 2.2.0 stable (dependency update)\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4203",children:"#4203"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/arixmkii",children:"arixmkii"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: index inline extension methods in ScalaToplevelMtags\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4199",children:"#4199"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feature: Update Ammonite runner for Scala 3 and latest Scala 2 versions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4197",children:"#4197"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: Support Scala 3.2.0-RC3\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4198",children:"#4198"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: Fold the end line of template / block if it's braceless\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4191",children:"#4191"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Print local type aliases properly\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4188",children:"#4188"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add match keyword completion\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4185",children:"#4185"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: request configuration before connecting to build server\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4180",children:"#4180"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Code Actions doc page ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4157",children:"#4157"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/vzmerr",children:"vzmerr"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: Remember choice ",(0,n.jsx)(t.code,{children:"Don't show this again"})," for sbt as build server\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4175",children:"#4175"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: [Scala 3] Show correct param names in java methods\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4179",children:"#4179"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: return all inversed dependencies in ",(0,n.jsx)(t.code,{children:"inverseDependenciesAll"}),"\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4176",children:"#4176"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["refactor: Use MetalsNames in ExtractValue code action\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4173",children:"#4173"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feat: Import missing extension method\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4141",children:"#4141"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Use the correct RC version of 3.2.0\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4171",children:"#4171"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Multiline string enhance\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4168",children:"#4168"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/vzmerr",children:"vzmerr"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["refactor: Print better debug infor when InferredType command failed\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4162",children:"#4162"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: use sonatypeOssRepos instead of sonatypeRepo\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4169",children:"#4169"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["docs: Add architecture.md\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4008",children:"#4008"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Include method signature in the label\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4161",children:"#4161"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add convertToNamedParameters support for Scala 3\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4131",children:"#4131"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bughack: Force specific Scala 3 compiler for worksheets\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4153",children:"#4153"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Show better titles for ConvertToNamedArguments code action\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4158",children:"#4158"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix triple quoted new line on type formatting\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4151",children:"#4151"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/vzmerr",children:"vzmerr"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update flyway-core from 9.0.0 to 9.0.1\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4159",children:"#4159"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Allow completions in multiline expressions when debugging\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4111",children:"#4111"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: [Scala 2] Automatically import types in string interpolations\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4140",children:"#4140"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Extend extract value with new cases\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4139",children:"#4139"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Bump sbt-dependency-submission\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4155",children:"#4155"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feat: Adding stub implementations for abstract given instances\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4055",children:"#4055"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["refactor: Show more debug messages\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4150",children:"#4150"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Reenable RenameLspSuite\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4152",children:"#4152"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["support for partial function\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4125",children:"#4125"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/zmerr",children:"zmerr"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update flyway-core from 8.5.13 to 9.0.0\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4147",children:"#4147"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update sbt, scripted-plugin from 1.7.0 to 1.7.1\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4148",children:"#4148"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: avoid duplicate migration appllication\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4144",children:"#4144"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["removing brace of for comprehension\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4137",children:"#4137"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/vzmerr",children:"vzmerr"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: update git-ignore-revs\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4145",children:"#4145"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: change formatting of trailing commas\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4050",children:"#4050"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update interface from 1.0.6 to 1.0.8\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4135",children:"#4135"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update sbt from 1.6.2 to 1.7.0\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4136",children:"#4136"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: Dialect should be scala21xSource3 for ",(0,n.jsx)(t.code,{children:"-Xsource:3.x.x"}),"\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4134",children:"#4134"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: refactor update test cases\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4129",children:"#4129"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Occurence highlight did not work for local vars\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4109",children:"#4109"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Save fingerprints between restarts\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4127",children:"#4127"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["[docs] Update Maven integration launcher to 2.13\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4130",children:"#4130"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/keynmol",children:"keynmol"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feature: Add a way to turn on debug logging and fix scalafix warmup\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4124",children:"#4124"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update scribe, scribe-file, scribe-slf4j from 3.10.0 to 3.10.1\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4128",children:"#4128"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["java version through shell\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4067",children:"#4067"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/zmerr",children:"zmerr"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["fix: do not start debug session for test explorer if projects contains errors\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4116",children:"#4116"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update mill-contrib-testng from 0.10.4 to 0.10.5\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4119",children:"#4119"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["add scala 3.2.0-RC2 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4118",children:"#4118"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update jsoup from 1.15.1 to 1.15.2\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4120",children:"#4120"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["build(deps): Update ipcsocket from 1.4.1 to 1.5.0\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4122",children:"#4122"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: [Scala 3] Improve constant and refined types printing\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4117",children:"#4117"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["docs: Add documentation for running scalafix in Metals\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4108",children:"#4108"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Build.sc was seen as Ammonite script\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4106",children:"#4106"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update sbt-dependency-graph\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4110",children:"#4110"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Refresh decorations even if empty to clear them out\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4104",children:"#4104"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["bugfix: Include entire expression in filterText for member itnerpolat\u2026\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4105",children:"#4105"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feat: ScaladocCompletion for Scala3\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4076",children:"#4076"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feat: update test suite location\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4073",children:"#4073"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["feature: [Scala 3] Add interpolation completions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4061",children:"#4061"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["docs: Fix wording about the expression evaluation\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4101",children:"#4101"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["docs: Add missing release notes section\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4097",children:"#4097"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["chore: Fix links in the new release docs\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4096",children:"#4096"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["release: Add release notes for Metals 0.11.7\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4083",children:"#4083"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,a.a)(),...e.components};return t?(0,n.jsx)(t,{...e,children:(0,n.jsx)(o,{...e})}):o(e)}},65:function(e,t,s){s.d(t,{Z:function(){return h},a:function(){return l}});var i=s(7294);let n={},a=i.createContext(n);function l(e){let t=i.useContext(a);return i.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function h(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(n):e.components||n:l(e.components),i.createElement(a.Provider,{value:t},e.children)}},8495:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2022/08/10/aluminium","source":"@site/blog/2022-08-10-aluminium.md","title":"Metals v0.11.8 - Aluminium","description":"We\'re happy to announce the release of Metals v0.11.8, bringing a number of","date":"2022-08-10T00:00:00.000Z","tags":[],"readingTime":6.695,"hasTruncateMarker":false,"authors":[{"name":"Rikito Taniguchi","url":"https://twitter.com/tanishiking25","imageURL":"https://github.com/tanishiking.png","key":"tanishiking","page":null}],"frontMatter":{"authors":"tanishiking","title":"Metals v0.11.8 - Aluminium"},"unlisted":false,"prevItem":{"title":"Metals v0.11.9 - Aluminium","permalink":"/metals/blog/2022/10/06/aluminium"},"nextItem":{"title":"Metals v0.11.7 - Aluminium","permalink":"/metals/blog/2022/07/04/aluminium"}}')}}]);