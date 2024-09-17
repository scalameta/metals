/*! For license information please see fda909f7.b81caf18.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[263],{9800:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>c,contentTitle:()=>i,default:()=>d,frontMatter:()=>n,metadata:()=>r,toc:()=>h});var a=s(4848),l=s(8453);const n={author:"Vadim Chelyshov",title:"Metals v0.10.5 - Tungsten",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},i=void 0,r={permalink:"/metals/blog/2021/07/14/tungsten",source:"@site/blog/2021-07-14-tungsten.md",title:"Metals v0.10.5 - Tungsten",description:"We're happy to announce the release of Metals v0.10.5, which contains a lot of fixes, adds Scala 3.0.1 support,",date:"2021-07-14T00:00:00.000Z",tags:[],readingTime:5.7,hasTruncateMarker:!1,authors:[{name:"Vadim Chelyshov",url:"https://twitter.com/_dos65",imageURL:"https://github.com/dos65.png",key:null,page:null}],frontMatter:{author:"Vadim Chelyshov",title:"Metals v0.10.5 - Tungsten",authorURL:"https://twitter.com/_dos65",authorImageURL:"https://github.com/dos65.png"},unlisted:!1,prevItem:{title:"Metals v0.10.6 - Tungsten",permalink:"/metals/blog/2021/09/06/tungsten"},nextItem:{title:"Metals v0.10.4 - Tungsten",permalink:"/metals/blog/2021/05/31/tungsten"}},c={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Add support for <code>textDocument/selectionRange</code>",id:"add-support-for-textdocumentselectionrange",level:2},{value:"[Scala3] <code>Inferred Type</code> code action and other improvements",id:"scala3-inferred-type-code-action-and-other-improvements",level:2},{value:"Better support of scalafmt for Scala 3 projects",id:"better-support-of-scalafmt-for-scala-3-projects",level:2},{value:"Search symbol references from dependency source in the workspace",id:"search-symbol-references-from-dependency-source-in-the-workspace",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.10.5 (2021-07-12)",id:"v0105-2021-07-12",level:2}];function o(e){const t={a:"a",code:"code",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,l.R)(),...e.components};return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.10.5, which contains a lot of fixes, adds Scala 3.0.1 support,\nand brings another nice feature!"}),"\n",(0,a.jsx)("table",{children:(0,a.jsxs)("tbody",{children:[(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Commits since last release"}),(0,a.jsx)("td",{align:"center",children:"155"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Merged PRs"}),(0,a.jsx)("td",{align:"center",children:"73"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Contributors"}),(0,a.jsx)("td",{align:"center",children:"9"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Closed issues"}),(0,a.jsx)("td",{align:"center",children:"18"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"New features"}),(0,a.jsx)("td",{align:"center",children:"1"})]})]})}),"\n",(0,a.jsxs)(t.p,{children:["For full details: ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/40?closed=1",children:"https://github.com/scalameta/metals/milestone/40?closed=1"})]}),"\n",(0,a.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text and Eclipse. Metals is developed at the\n",(0,a.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,a.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,a.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,a.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,a.jsxs)(t.p,{children:["Check out ",(0,a.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["Add support for ",(0,a.jsx)(t.code,{children:"textDocument/selectionRange"})]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala 3] ",(0,a.jsx)(t.code,{children:"Inferred Type"})," code action and other improvements"]}),"\n",(0,a.jsx)(t.li,{children:"Better support of scalafmt for Scala 3 projects"}),"\n",(0,a.jsx)(t.li,{children:"Search symbol references from dependency source in the workspace"}),"\n"]}),"\n",(0,a.jsxs)(t.h2,{id:"add-support-for-textdocumentselectionrange",children:["Add support for ",(0,a.jsx)(t.code,{children:"textDocument/selectionRange"})]}),"\n",(0,a.jsxs)(t.p,{children:["Due to the great work by ",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"@ckipp01"})," Metals now implements the ",(0,a.jsx)(t.code,{children:"textDocument/selectionRange"})," ",(0,a.jsx)(t.a,{href:"https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocument_selectionRange",children:"LSP-method"}),".\nIt allows you to easily select an expression and expand/shrink it using shortcut combinations:"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://user-images.githubusercontent.com/13974112/125335989-7a5f8780-e34d-11eb-911f-42f851478737.gif",alt:"gif1"})}),"\n",(0,a.jsx)(t.p,{children:"Default keybindings in VSCode:"}),"\n",(0,a.jsx)("table",{children:(0,a.jsxs)("tbody",{style:{"font-size":"13px"},children:[(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{}),(0,a.jsx)("td",{children:"Win/Linux"}),(0,a.jsx)("td",{children:"macOS"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Expand"}),(0,a.jsx)("td",{children:"Shift+Alt+Right"}),(0,a.jsx)("td",{children:"\u2303\u21e7\u2318\u2192"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Shrink"}),(0,a.jsx)("td",{children:"Shift+Alt+Left"}),(0,a.jsx)("td",{children:"\u2303\u21e7\u2318\u2190"})]})]})}),"\n",(0,a.jsxs)(t.h2,{id:"scala3-inferred-type-code-action-and-other-improvements",children:["[Scala3] ",(0,a.jsx)(t.code,{children:"Inferred Type"})," code action and other improvements"]}),"\n",(0,a.jsxs)(t.p,{children:["We're hard at work ensuring that the same feature-set for Scala 2 is available for Scala 3.\nThis release adds support for the ",(0,a.jsx)(t.code,{children:"Insert Inferred Type"})," code action."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://i.imgur.com/GJGFOOy.gif",alt:"gif2"})}),"\n",(0,a.jsxs)(t.p,{children:["In addition to other small fixes, it's worth mentioning that the ",(0,a.jsx)(t.code,{children:"Go to Definition"})," functionality was improved so that in situations where there are compilation issues, you're now able to better navigate to the definition site."]}),"\n",(0,a.jsx)(t.h2,{id:"better-support-of-scalafmt-for-scala-3-projects",children:"Better support of scalafmt for Scala 3 projects"}),"\n",(0,a.jsxs)(t.p,{children:["In order to support different Scala dialects scalafmt added a ",(0,a.jsxs)(t.a,{href:"https://scalameta.org/scalafmt/docs/configuration.html#scala-3",children:[(0,a.jsx)(t.code,{children:"runner.dialect"})," setting"]})," to support Scala 3.\nMetals will now correctly generate and even automatically upgrade your ",(0,a.jsx)(t.code,{children:".scalafmt.conf"}),".\nMoreover, in projects that contain different Scala major versions Metals will set a separate dialect for each separate source directory."]}),"\n",(0,a.jsx)(t.h2,{id:"search-symbol-references-from-dependency-source-in-the-workspace",children:"Search symbol references from dependency source in the workspace"}),"\n",(0,a.jsxs)(t.p,{children:["Thanks to ",(0,a.jsx)(t.a,{href:"https://github.com/Z1kkurat",children:"@Z1kkurat"}),' "Find References" now also works from within your dependency sources to include references from all workspace sources.\nPreviously, it included only local occurrences in the dependency source file.']}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://i.imgur.com/myHPDjP.gif",alt:"gif3"})}),"\n",(0,a.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"Fixed issues with Metals client still running after closing window"}),"\n",(0,a.jsx)(t.li,{children:"Fixed indexing of Scala 3 libraries that include macros"}),"\n",(0,a.jsx)(t.li,{children:"Fixed issues with breakpoints not stopped when using optional braces"}),"\n",(0,a.jsx)(t.li,{children:"Print type decorations for synthetic apply"}),"\n",(0,a.jsx)(t.li,{children:"Wrap autofilled arguments with backticks"}),"\n",(0,a.jsx)(t.li,{children:"Don't offer to organize imports when there is an error in the file"}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,a.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.10.4..v0.10.5\n22\tChris Kipp\n18\tVadim Chelyshov\n17\tTomasz Godzik\n 2\tAdrien Piquerez\n 2\tLuigi Frunzio\n 2\tKamil Podsiad\u0142o\n 1\tZ1kkurat\n"})}),"\n",(0,a.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,a.jsxs)(t.h2,{id:"v0105-2021-07-12",children:[(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.10.5",children:"v0.10.5"})," (2021-07-12)"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.10.4...v0.10.5",children:"Full Changelog"})}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["Add support for Scala 3.0.2-RC1 and drop references to 3.0.0-RC\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2955",children:"#2955"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add Scala 3.0.1 support\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2947",children:"#2947"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update vim docs now that Neovim 0.5.0 is out.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2951",children:"#2951"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't set detail in completion if it's empty.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2950",children:"#2950"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Organize imports quickfix action\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2935",children:"#2935"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Fix completion pos inference for Select with nme.ERROR\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2940",children:"#2940"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala 3] Properly show enum on hover\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2938",children:"#2938"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Correct footer label\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2939",children:"#2939"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala2] PcDefinition fix\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2917",children:"#2917"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix dead links and add in serve command\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2937",children:"#2937"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Fix completions after newline + dot\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2930",children:"#2930"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update deploy command for docusaurus.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2936",children:"#2936"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update to Docusaurus 2.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2927",children:"#2927"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:['Revert "Merge pull request #2908 from tgodzik/reset-object"\n',(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2931",children:"#2931"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't offer to organize imports when there is an error in the file\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2921",children:"#2921"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Do not use $TMPDIR to store sbt-launcher.jar\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2924",children:"#2924"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpodsiad",children:"kpodsiad"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Wrap autofilled arguments with backticks\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2929",children:"#2929"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Print type decorations for synthetic apply\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2906",children:"#2906"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Use symbol search for PC ",(0,a.jsx)(t.code,{children:"definitions"}),".\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2900",children:"#2900"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Wrap worksheets in object instead of a class\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2908",children:"#2908"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Search for symbol references in workspace\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2920",children:"#2920"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Z1kkurat",children:"Z1kkurat"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalameta, semanticdb-scalac, ... to 4.4.23\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2915",children:"#2915"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix unclear heading in sbt docs.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2916",children:"#2916"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update ipcsocket to 1.4.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2913",children:"#2913"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update lz4-java to 1.8.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2912",children:"#2912"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 7.10.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2911",children:"#2911"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Rework ",(0,a.jsx)(t.code,{children:"NamesInScope"}),"\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2867",children:"#2867"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add support for Scala 3.0.1-RC2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2898",children:"#2898"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix off by one error while setting DAP breakpoints\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2894",children:"#2894"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Exclude Jmh and Scalafix from the list of build targets in sbt\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2895",children:"#2895"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Provide correct dialect to ",(0,a.jsx)(t.code,{children:"Mtags.allToplevels"})," calls.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2892",children:"#2892"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix CompilerJobQueue concurrent access/shutdown\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2890",children:"#2890"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Get rid of warning about setting -Xsemanticdb twice.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2889",children:"#2889"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scalafmt] Rewrite ",(0,a.jsx)(t.code,{children:".scalafmt.conf"})," if sources require non-default dialect\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2814",children:"#2814"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add support for ",(0,a.jsx)(t.code,{children:"textDocument/selectionRange"}),".\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2862",children:"#2862"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Ensure a valid URI is returned for non-html analyze stacktrace.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2876",children:"#2876"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalameta, semanticdb-scalac, ... to 4.4.21\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2886",children:"#2886"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update ipcsocket to 1.3.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2884",children:"#2884"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalafmt-core to 3.0.0-RC5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2885",children:"#2885"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt, scripted-plugin to 1.5.4\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2883",children:"#2883"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 7.9.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2882",children:"#2882"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-jmh to 0.4.3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2887",children:"#2887"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update ujson to 1.4.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2881",children:"#2881"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump sbt-debug-adapter to 1.1.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2872",children:"#2872"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/adpi2",children:"adpi2"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Lazily get explicit choice.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2868",children:"#2868"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala 3] Enable inferred type code action\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2825",children:"#2825"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't offer completions inside scaladoc and comments\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2822",children:"#2822"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Giggiux",children:"Giggiux"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["WorkspaceSearchVisitior - make search more predictable.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2863",children:"#2863"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Enable organize-imports for mtags3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2860",children:"#2860"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Run organize imports on Scala 3 sources\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2858",children:"#2858"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Enable organize imports for Scala 3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2857",children:"#2857"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["[Scala3] Add test that checks that diagnostic for inline def usage works correctly\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2854",children:"#2854"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add support for Scala 3.0.1-RC1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2852",children:"#2852"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump olafurpg/setup-scala from 10 to 12\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2848",children:"#2848"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["ScalaVersions: fix scala3 version extraction from jar name.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2833",children:"#2833"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update amonite to 2.3.8-124-2da846d2 and add support for 2.13.6\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2851",children:"#2851"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Revert Bloop version upgrade\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2850",children:"#2850"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix scalafmt config\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2849",children:"#2849"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalafmt-core to 3.0.0-RC4\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2845",children:"#2845"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update jol-core to 0.16\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2844",children:"#2844"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 7.9.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2843",children:"#2843"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update undertow-core to 2.2.8.Final\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2842",children:"#2842"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update nuprocess to 2.0.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2841",children:"#2841"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scribe, scribe-file, scribe-slf4j to 3.5.5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2840",children:"#2840"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update ujson to 1.3.15\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2839",children:"#2839"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update mill-contrib-testng to 0.9.8\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2838",children:"#2838"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump coursier/cache-action from 5 to 6.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2846",children:"#2846"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update metaconfig-core to 0.9.14\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2836",children:"#2836"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalafix-interfaces to 0.9.29\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2835",children:"#2835"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update bloop-config, bloop-launcher to 1.4.8-43-c2d941d9\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2834",children:"#2834"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add FAQ entry about sbt Apple M1 issue\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2832",children:"#2832"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Minor fix in last release notes\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2831",children:"#2831"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add release notes - 0.10.4 release\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2830",children:"#2830"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,l.R)(),...e.components};return t?(0,a.jsx)(t,{...e,children:(0,a.jsx)(o,{...e})}):o(e)}},1020:(e,t,s)=>{var a=s(6540),l=Symbol.for("react.element"),n=Symbol.for("react.fragment"),i=Object.prototype.hasOwnProperty,r=a.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,c={key:!0,ref:!0,__self:!0,__source:!0};function h(e,t,s){var a,n={},h=null,o=null;for(a in void 0!==s&&(h=""+s),void 0!==t.key&&(h=""+t.key),void 0!==t.ref&&(o=t.ref),t)i.call(t,a)&&!c.hasOwnProperty(a)&&(n[a]=t[a]);if(e&&e.defaultProps)for(a in t=e.defaultProps)void 0===n[a]&&(n[a]=t[a]);return{$$typeof:l,type:e,key:h,ref:o,props:n,_owner:r.current}}t.Fragment=n,t.jsx=h,t.jsxs=h},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>i,x:()=>r});var a=s(6540);const l={},n=a.createContext(l);function i(e){const t=a.useContext(n);return a.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:i(e.components),a.createElement(n.Provider,{value:t},e.children)}}}]);