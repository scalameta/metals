"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["7623"],{12146:function(e,t,s){s.r(t),s.d(t,{assets:function(){return h},contentTitle:function(){return r},default:function(){return d},frontMatter:function(){return l},metadata:function(){return i},toc:function(){return c}});var i=s(7912),a=s(85893),n=s(50065);let l={authors:"kmarek",title:"Metals v0.11.12 - Aluminium"},r=void 0,h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Introducing Error Reports",id:"introducing-error-reports",level:2},{value:"New code action for converting dependencies from sbt to scala-cli compliant ones",id:"new-code-action-for-converting-dependencies-from-sbt-to-scala-cli-compliant-ones",level:2},{value:"Improvements to semantic highlighting",id:"improvements-to-semantic-highlighting",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.11.12 (2023-04-21)",id:"v01112-2023-04-21",level:2}];function o(e){let t={a:"a",code:"code",em:"em",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,n.a)(),...e.components};return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.11.12. This release brings\nmostly stability fixes, some various improvements, and a start towards better\nissue identification and reporting when users experience issues."}),"\n",(0,a.jsx)("table",{children:(0,a.jsxs)("tbody",{children:[(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Commits since last release"}),(0,a.jsx)("td",{align:"center",children:"105"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Merged PRs"}),(0,a.jsx)("td",{align:"center",children:"85"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Contributors"}),(0,a.jsx)("td",{align:"center",children:"20"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Closed issues"}),(0,a.jsx)("td",{align:"center",children:"38"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"New features"}),(0,a.jsx)("td",{align:"center",children:"5"})]})]})}),"\n",(0,a.jsxs)(t.p,{children:["For full details: [",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/56?closed=1",children:"https://github.com/scalameta/metals/milestone/56?closed=1"}),"]","\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/56?closed=1",children:"https://github.com/scalameta/metals/milestone/56?closed=1"}),")"]}),"\n",(0,a.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs and\nSublime Text. Metals is developed at the ",(0,a.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"}),"\nand ",(0,a.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"})," with the help from\n",(0,a.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from the community."]}),"\n",(0,a.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,a.jsxs)(t.p,{children:["Check out ",(0,a.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.a,{href:"#introducing-error-reports",children:"Introducing Error Reports"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.a,{href:"#new-code-action-for-converting-dependencies-from-sbt-to-scala-cli-compliant-ones",children:"New code action for converting dependencies from sbt to scala-cli compliant ones"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.a,{href:"#improvements-to-semantic-highlighting",children:"Improvements to semantic highlighting"})}),"\n",(0,a.jsx)(t.li,{children:(0,a.jsx)(t.a,{href:"#miscellaneous",children:"Miscellaneous"})}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"introducing-error-reports",children:"Introducing Error Reports"}),"\n",(0,a.jsxs)(t.p,{children:["Starting with this release, upon chosen errors or incorrect states in metals\nerror reports will be automatically generated and saved in the\n",(0,a.jsx)(t.code,{children:".metals/.reports"})," directory within the workspace. Such an error report can\nlater be used by users to attach to a github issue with additional information.\nAll the reports have their paths anonymised."]}),"\n",(0,a.jsx)(t.p,{children:"Currently, we create two types of reports:"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:[(0,a.jsx)(t.em,{children:"incognito"})," under ",(0,a.jsx)(t.code,{children:".metals/.reports/metals"}),": these error reports contain only\nmetals related stacktraces and should not contain any sensitive/private\ninformation"]}),"\n",(0,a.jsxs)(t.li,{children:[(0,a.jsx)(t.em,{children:"unsanitized"})," under ",(0,a.jsx)(t.code,{children:".metals/.reports/metals-full"}),": these error reports may\ncontain come sensitive/private information (e.g. code snippets)"]}),"\n"]}),"\n",(0,a.jsxs)(t.p,{children:["To make it easier to attach reports to github issues the ",(0,a.jsx)(t.code,{children:"zip-reports"})," command\nwill zip all the ",(0,a.jsx)(t.strong,{children:"incognito"})," reports into ",(0,a.jsx)(t.code,{children:".metals/.reports/report.zip"}),", while\nunsanitized reports will have to be browsed and attached by hand. In order not\nto clutter the workspace too much, only up to 30 last reports of each kind are\nkept at a time."]}),"\n",(0,a.jsxs)(t.p,{children:["When running into a problem in VSCode (or any editor that implements this\ncommand) users can use the ",(0,a.jsx)(t.code,{children:"Metals: Open new github issue"})," command. This will\ncreate a template for an issue with all basic info such as Metals version, used\nbuild server etc.. Next, you can browse through reports and drag and drop chosen\nones to your GitHub issue. Invoking the ",(0,a.jsx)(t.code,{children:"Metals: Zip reports"})," command will\ncreate a zip of all the incognito that reports can also be included in the\nissue."]}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.img,{src:"https://i.imgur.com/wBwFjpZ.gif",alt:"Reports"}),"\n",(0,a.jsx)(t.img,{src:"https://i.imgur.com/YN3U3N9.gif",alt:"Zip reports"})]}),"\n",(0,a.jsx)(t.h2,{id:"new-code-action-for-converting-dependencies-from-sbt-to-scala-cli-compliant-ones",children:"New code action for converting dependencies from sbt to scala-cli compliant ones"}),"\n",(0,a.jsxs)(t.p,{children:["New code action for scala-cli ",(0,a.jsx)(t.code,{children:"using (dep | lib | plugin)"})," directives, which\nallows to convert dependencies from sbt style to scala-cli compliant ones."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://i.imgur.com/G9W7Nox.gif",alt:"Convert dependency"})}),"\n",(0,a.jsxs)(t.p,{children:["A great contribution by ",(0,a.jsx)(t.a,{href:"https://github.com/majk-p",children:"majk-p"}),"."]}),"\n",(0,a.jsx)(t.h2,{id:"improvements-to-semantic-highlighting",children:"Improvements to semantic highlighting"}),"\n",(0,a.jsxs)(t.p,{children:["This release brings a lot of improvements for semantic highlighting thanks to\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pulls/jkciesluk",children:"jkciesluk"})," and\n",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),". This includes:"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"Added declaration and definition tokens."}),"\n",(0,a.jsx)(t.li,{children:"Parameters are now read only."}),"\n",(0,a.jsx)(t.li,{children:"Added semantic highlighting for using directives."}),"\n",(0,a.jsx)(t.li,{children:"Fixed semantic highlighting for Scala 3 worksheets."}),"\n",(0,a.jsx)(t.li,{children:'Changed token type for predef aliases to "class".'}),"\n",(0,a.jsx)(t.li,{children:"Fixed sematic highlighting in sbt files."}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["bugfix: Ignored tests now show up in the test explorer.\n",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"})]}),"\n",(0,a.jsx)(t.li,{children:"improvement: Reworked package rename upon file move."}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fixed go to and hover for ",(0,a.jsx)(t.code,{children:"TypeTest"}),".\n",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["feature: Auto connect to bazel-bsp if it's installed.\n",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"})]}),"\n",(0,a.jsxs)(t.li,{children:["docs: Updated emacs support table. ",(0,a.jsx)(t.a,{href:"https://github.com/kurnevsky",children:"kurnevsky"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Placing cursor on primary contructor type parameter no longer\nincorrectly highlights type parameter with the same name used in a member\ndefiniton. ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Added Reload sbt after changes in the ",(0,a.jsx)(t.code,{children:"metals.sbt"})," plugin file.\n",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"})]}),"\n",(0,a.jsx)(t.li,{children:"bugfix: Added handling fixing wildcard imports upon file rename."}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Added refresh test case code lenses after test discovery.\n",(0,a.jsx)(t.a,{href:"https://github.com/LaurenceWarne",children:"LaurenceWarne"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fixed lacking newline for new imports added upon file move.\n",(0,a.jsx)(t.a,{href:"https://github.com/susliko",children:"susliko"})]}),"\n",(0,a.jsx)(t.li,{children:"bugfix: Add showing lenses when BSP server is plain Scala."}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: A workaround for running BSP sbt when it's installed in a directory\nwith a space on Widows. ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsx)(t.li,{children:"improvement: If an aliased inferred type is not in scope dealias it."}),"\n",(0,a.jsxs)(t.li,{children:["feature: Add hover information for structural types.\n",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"})]}),"\n",(0,a.jsx)(t.li,{children:"feature: Inline code action will be no longer executed if any of the\nreferences used on the right-hand side of the value to be inlined are shadowed\nby local definitions. In this case a warning will be shown to the user\ninstead."}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fixed test suite discovery in presence of a companion object.\n",(0,a.jsx)(t.a,{href:"https://github.com/xydrolase",children:"xydrolase"})]}),"\n",(0,a.jsx)(t.li,{children:"bugfix: Fixed shown return type of completions, that are no argument members,\nwhich return type depends on the ower type parameter."}),"\n",(0,a.jsx)(t.li,{children:"bugfix: Strip ANSI colors before printing worksheet results."}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Force close thread when file watching cancel hangs.\n",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Add end condition for tokenizing partially written code, so tokenizing\ndoesn't hang. ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Correctly adjust span for extension methods for correctly displayed\nhighligh. ",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Correctly show ",(0,a.jsx)(t.code,{children:"Expression type"})," (dealiased type) for parametrized\ntypes."]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Filter out ",(0,a.jsx)(t.code,{children:"-Ycheck-reentrant"})," option for worksheets, so worksheets\ncorrectly show results. ",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Show correct defaults when named parameters order is mixed in Scala 2.\n",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"})]}),"\n",(0,a.jsx)(t.li,{children:"bugfix: Print better constructors in synthetic decorator."}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Show synthetic objects as options for case classes and AnyVal implicit\nclasses in ",(0,a.jsx)(t.code,{children:"Metals Analayze Source"}),". ",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"})]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Corrrectly handle testfiles renames in test explorer.\n",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"})]}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,a.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.11.11..v0.11.12\n    23	scalameta-bot\n    21	Tomasz Godzik\n    11	Katarzyna Marek\n    10	Jakub Ciesluk\n     7	Michal Pawlik\n     6	Kamil Podsiadlo\n     5	Rikito Taniguchi\n     3	adpi2\n     3	Kamil Podsiad\u0142o\n     3	Micha\u0142 Pawlik\n     3	dependabot[bot]\n     2	tgodzik\n     1	Chris Kipp\n     1	Vadim Chelyshov\n     1	Vasiliy Morkovkin\n     1	Xin Yin\n     1	Laurence Warne\n     1	J\u0119drzej Rochala\n     1	Evgeny Kurnevsky\n     1	Scalameta Bot\n"})}),"\n",(0,a.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,a.jsxs)(t.h2,{id:"v01112-2023-04-21",children:[(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.11.12",children:"v0.11.12"})," (2023-04-21)"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.11.11...v0.11.12",children:"Full Changelog"})}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scalameta, semanticdb-scalac, ... from 4.7.1 to 4.7.7\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5163",children:"#5163"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update coursier from 2.1.1 to 2.1.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5162",children:"#5162"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update semanticdb-java from 0.8.13 to 0.8.15\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5161",children:"#5161"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Add support for Scala 3.3.0-RC4\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5158",children:"#5158"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: mtags compilation with Java 11\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5157",children:"#5157"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Reduce IO when querying if file exists\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5152",children:"#5152"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix Scala CLI script for release notes\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5151",children:"#5151"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: properly handle testfiles renames in Test Explorer\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5042",children:"#5042"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add a description about MtagsIndexer in architecture.md\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4794",children:"#4794"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["feature: properly index Java sources\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5009",children:"#5009"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update coursier from 2.1.0 to 2.1.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5142",children:"#5142"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix test after changes in #5124\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5147",children:"#5147"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore[skip ci]: Switch nightly releases to branch with fixed test\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5145",children:"#5145"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update interface from 1.0.14 to 1.0.15\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5143",children:"#5143"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Auto spawn/connect to bazel-bsp if it's installed\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5139",children:"#5139"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: report on an empty hover\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5128",children:"#5128"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update flyway-core from 9.16.1 to 9.16.3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5136",children:"#5136"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update emacs support table\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5131",children:"#5131"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kurnevsky",children:"kurnevsky"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Show document highlight with cursor on primary cosntructor\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5127",children:"#5127"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Update mima last artifacts and make an explicit step in the docs\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5130",children:"#5130"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Reload sbt after writing metals plugin\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5126",children:"#5126"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: handling wildcard imports for file rename\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4986",children:"#4986"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Refresh test case code lenses after discovery\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5124",children:"#5124"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/LaurenceWarne",children:"LaurenceWarne"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: add newline in new imports when moving files\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5120",children:"#5120"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/susliko",children:"susliko"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Show lenses when BSP server is plain Scala\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5118",children:"#5118"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix running BSP sbt when it's installed in a directory with space\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5107",children:"#5107"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Make fallback semantic tokens readonly\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5067",children:"#5067"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Extract shared mtags without scalameta dependency\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5075",children:"#5075"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/rochala",children:"rochala"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): bump @docusaurus/plugin-client-redirects from 2.3.1 to 2.4.0 in\n/website ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5111",children:"#5111"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update xnio-nio from 3.8.8.Final to 3.8.9.Final\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5113",children:"#5113"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): bump @docusaurus/preset-classic from 2.3.1 to 2.4.0 in /website\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5109",children:"#5109"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update interface from 1.0.13 to 1.0.14\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5112",children:"#5112"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): bump @docusaurus/core from 2.3.1 to 2.4.0 in /website\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5110",children:"#5110"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix go to definition and hover for TypeTest\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5103",children:"#5103"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Add definition and declaration properties\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5047",children:"#5047"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update sbt-mima-plugin from 1.1.1 to 1.1.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5104",children:"#5104"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update versions from 0.3.1 to 0.3.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5105",children:"#5105"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scalafmt-core, scalafmt-dynamic from 3.7.2 to 3.7.3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5106",children:"#5106"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: pkgs renames for file move\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5014",children:"#5014"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Code action to convert dependencies from sbt to mill style\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5078",children:"#5078"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/majk-p",children:"majk-p"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Change token type to class for predef aliases\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5044",children:"#5044"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: dealias inferred types if not in scope\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5051",children:"#5051"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix semantic highlight in sbt files\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5098",children:"#5098"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["feat: Hover on structural types in Scala 3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5074",children:"#5074"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix #5096: run doc on java target with semanticdb\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5097",children:"#5097"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update mill-contrib-testng from 0.10.11 to 0.10.12\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5081",children:"#5081"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update sbt-jmh from 0.4.3 to 0.4.4\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5094",children:"#5094"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update cli_3, scala-cli-bsp from 0.1.20 to 0.2.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5093",children:"#5093"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scalafmt-core, scalafmt-dynamic from 3.6.1 to 3.7.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5091",children:"#5091"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update flyway-core from 9.15.2 to 9.16.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5090",children:"#5090"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update mdoc, mdoc-interfaces, sbt-mdoc from 2.3.6 to 2.3.7\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5089",children:"#5089"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update jol-core from 0.16 to 0.17\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5088",children:"#5088"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update flyway-core from 9.15.0 to 9.15.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5086",children:"#5086"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update jsoup from 1.15.3 to 1.15.4\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5087",children:"#5087"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update org.eclipse.lsp4j, ... from 0.20.0 to 0.20.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5085",children:"#5085"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update coursier from 2.1.0-RC6 to 2.1.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5084",children:"#5084"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scribe, scribe-file, scribe-slf4j from 3.11.0 to 3.11.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5083",children:"#5083"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["build(deps): Update scala-debug-adapter from 3.0.7 to 3.0.9\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5080",children:"#5080"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scalameta-bot",children:"scalameta-bot"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["refactor: use scala-cli for script instead of Ammonite\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5077",children:"#5077"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["feat: find companion object of AnyVal, implicit class in class finder\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4974",children:"#4974"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["docs: Switch from algolia to easyops-cn/docusaurus-search-local\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5066",children:"#5066"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: Adjust semantic tokens tests for select dynamic in 3.3.1-RC1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5069",children:"#5069"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: Fix select dynamic hover tests for 3.3.1-RC1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5068",children:"#5068"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Make semantic tokens parameters readonly\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5049",children:"#5049"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: print better constructors in synthetic decorator\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5023",children:"#5023"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["feature: metals error reports\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4971",children:"#4971"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix wrong default when named params are mixed\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5058",children:"#5058"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Filter out -Ycheck-reentrant\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5059",children:"#5059"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["chore: use value class to for new semanticDB path type\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5043",children:"#5043"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["docs: Welcome Kasia to the team\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5054",children:"#5054"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: Check if span in def is correct\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5046",children:"#5046"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["feat: discover Scalatest ignored tests\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5035",children:"#5035"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Add semantic highlight for using directives\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5037",children:"#5037"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: dealias applied type params\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5048",children:"#5048"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fix span for extension methods\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5040",children:"#5040"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Make sure that tokenizing doesn't hang\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5050",children:"#5050"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: Force closing thread if file watching cancel hanged\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5010",children:"#5010"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix: Fix semantic tokens for worksheets in Scala 3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5038",children:"#5038"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jkciesluk",children:"jkciesluk"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: strip ANSI colours before printing worksheet results\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5039",children:"#5039"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: perform ",(0,a.jsx)(t.code,{children:"substituteTypeVars"})," also for methods with no args\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5045",children:"#5045"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Force update of scala-debug-adapter by Scala Steward\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5032",children:"#5032"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["bugfix: Fixed test suite discovery with the presence of companion object.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5030",children:"#5030"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/xydrolase",children:"xydrolase"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["docs: Link to Scala release\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/5022",children:"#5022"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["improvement: inline value scoping\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4943",children:"#4943"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kasiaMarek",children:"kasiaMarek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["docs: Add release notes for 0.11.11\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/4973",children:"#4973"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,n.a)(),...e.components};return t?(0,a.jsx)(t,{...e,children:(0,a.jsx)(o,{...e})}):o(e)}},50065:function(e,t,s){s.d(t,{Z:function(){return r},a:function(){return l}});var i=s(67294);let a={},n=i.createContext(a);function l(e){let t=i.useContext(n);return i.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:l(e.components),i.createElement(n.Provider,{value:t},e.children)}},7912:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2023/04/21/aluminium","source":"@site/blog/2023-04-21-aluminium.md","title":"Metals v0.11.12 - Aluminium","description":"We\'re happy to announce the release of Metals v0.11.12. This release brings","date":"2023-04-21T00:00:00.000Z","tags":[],"readingTime":9.27,"hasTruncateMarker":false,"authors":[{"name":"Katarzyna Marek","url":"https://github.com/kasiaMarek","imageURL":"https://github.com/kasiaMarek.png","key":"kmarek","page":null}],"frontMatter":{"authors":"kmarek","title":"Metals v0.11.12 - Aluminium"},"unlisted":false,"prevItem":{"title":"Workspace folders","permalink":"/metals/blog/2023/07/17/workspace-folders"},"nextItem":{"title":"Metals v0.11.11 - Aluminium","permalink":"/metals/blog/2023/03/02/aluminium"}}')}}]);