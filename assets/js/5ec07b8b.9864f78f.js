/*! For license information please see 5ec07b8b.9864f78f.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[8345],{1119:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>r,contentTitle:()=>i,default:()=>d,frontMatter:()=>n,metadata:()=>c,toc:()=>h});var a=s(4848),l=s(8453);const n={author:"Tomasz Godzik",title:"Metals v0.9.4 - Lithium",authorURL:"https://twitter.com/TomekGodzik",authorImageURL:"https://github.com/tgodzik.png"},i=void 0,c={permalink:"/metals/blog/2020/09/21/lithium",source:"@site/blog/2020-09-21-lithium.md",title:"Metals v0.9.4 - Lithium",description:"We're happy to announce the release of Metals v0.9.4, which focuses on",date:"2020-09-21T00:00:00.000Z",tags:[],readingTime:5.72,hasTruncateMarker:!1,authors:[{name:"Tomasz Godzik",url:"https://twitter.com/TomekGodzik",imageURL:"https://github.com/tgodzik.png",key:null,page:null}],frontMatter:{author:"Tomasz Godzik",title:"Metals v0.9.4 - Lithium",authorURL:"https://twitter.com/TomekGodzik",authorImageURL:"https://github.com/tgodzik.png"},unlisted:!1,prevItem:{title:"sbt BSP support",permalink:"/metals/blog/2020/11/06/sbt-BSP-support"},nextItem:{title:"Metals v0.9.3 - Lithium",permalink:"/metals/blog/2020/08/19/lithium"}},r={authorsImageUrls:[void 0]},h=[{value:"TL;DR",id:"tldr",level:2},{value:"Analyze stacktrace command",id:"analyze-stacktrace-command",level:2},{value:"Visual Studio Code",id:"visual-studio-code",level:3},{value:"coc-metals",id:"coc-metals",level:3},{value:"Other editors",id:"other-editors",level:3},{value:"Customizable package exclusions",id:"customizable-package-exclusions",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.9.4 (2020-09-21)",id:"v094-2020-09-21",level:2}];function o(e){const t={a:"a",code:"code",em:"em",h2:"h2",h3:"h3",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,l.R)(),...e.components};return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.9.4, which focuses on\nimprovements in Scala 3 worksheets, a couple of new features, and a number of\nsmaller improvements."}),"\n",(0,a.jsx)("table",{children:(0,a.jsxs)("tbody",{children:[(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Commits since last release"}),(0,a.jsx)("td",{align:"center",children:"115"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Merged PRs"}),(0,a.jsx)("td",{align:"center",children:"50"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Contributors"}),(0,a.jsx)("td",{align:"center",children:"6"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Closed issues"}),(0,a.jsx)("td",{align:"center",children:"15"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"New features"}),(0,a.jsx)("td",{align:"center",children:"2"})]})]})}),"\n",(0,a.jsxs)(t.p,{children:["For full details: ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/27?closed=1",children:"https://github.com/scalameta/metals/milestone/27?closed=1"})]}),"\n",(0,a.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text, Atom and Eclipse. Metals is developed at the\n",(0,a.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,a.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,a.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,a.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,a.jsxs)(t.p,{children:["Check out ",(0,a.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"Scala 0.26.0 and 0.27.0-RC1 support."}),"\n",(0,a.jsx)(t.li,{children:"Analyze stacktrace command"}),"\n",(0,a.jsx)(t.li,{children:"Customizable package exclusions"}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"analyze-stacktrace-command",children:"Analyze stacktrace command"}),"\n",(0,a.jsxs)(t.p,{children:["Thanks to ",(0,a.jsx)(t.a,{href:"https://github.com/kpbochenek",children:"kpbochenek"}),", Metals can now help with\nnavigating stack traces using the new ",(0,a.jsx)(t.code,{children:"metals.analyze-stacktrace"})," command. The\nonly thing it needs is the actual stack trace string to be input as a parameter\nto the command. This new functionality works differently in various editors."]}),"\n",(0,a.jsx)(t.h3,{id:"visual-studio-code",children:"Visual Studio Code"}),"\n",(0,a.jsxs)(t.p,{children:["The only thing users need to do is copy the stack trace and run the\n",(0,a.jsx)(t.code,{children:"Analyze Stacktrace"})," command from the command palette. This will display a new\nweb view, where it will be possible for users to navigate the files that are\nincluded in the exception."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://i.imgur.com/WBU4hvT.gif",alt:"stacktrace-vscode"})}),"\n",(0,a.jsx)(t.h3,{id:"coc-metals",children:"coc-metals"}),"\n",(0,a.jsxs)(t.p,{children:["With ",(0,a.jsx)(t.code,{children:"coc-metals"}),", you just need to copy the stacktrace, whether from sbt,\nbloop-cli, or somewhere else into your register. Once you have it copied, you\ncan just execute the ",(0,a.jsx)(t.code,{children:"metals.analyze-stacktrace"}),"command. This will create\na",(0,a.jsx)(t.code,{children:".metals/stracktrace.scala"})," file which can be used to navigate through your\nstacktrace using code lenses. Keep in mind that you'll want to make sure you\nhave codeLens.enable set to true in your configuration. Also, since this feature\nrelies on code lenses (virtual text in Nvim), it's only supported in Nvim."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://camo.githubusercontent.com/54a9cb68547532b2ff16cb6f95fdd8268d806b41/68747470733a2f2f692e696d6775722e636f6d2f74516a694147322e676966",alt:"stacktrace-nvim"})}),"\n",(0,a.jsx)(t.h3,{id:"other-editors",children:"Other editors"}),"\n",(0,a.jsxs)(t.p,{children:["To make it work with any other editors users need to run the command manually\nwith the stacktrace as input, similar to the example above with ",(0,a.jsx)(t.code,{children:"coc-metals"}),".\nThe command will generate a new file, ",(0,a.jsx)(t.code,{children:".metals/stacktrace.scala"}),", which can be\nused to navigate through the stacktrace using code lenses."]}),"\n",(0,a.jsx)(t.h2,{id:"customizable-package-exclusions",children:"Customizable package exclusions"}),"\n",(0,a.jsx)(t.p,{children:"Usually, a number of packages are almost never used by the developers and could\nclutter the language server output while being of little benefit to the users.\nMetals would exclude those packages from indexing causing them to not be\nrecommended for completions, symbol searches, and code actions. Previously that\nlist was set in stone and consisted of a number of prefixes:"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{className:"language-scala",children:'val defaultExclusions: List[String] = List(\n    "META-INF/", "images/", "toolbarButtonGraphics/", "jdk/", "sun/", "javax/",\n    "oracle/", "java/awt/desktop/", "org/jcp/", "org/omg/", "org/graalvm/",\n    "com/oracle/", "com/sun/", "com/apple/", "apple/"\n  )\n'})}),"\n",(0,a.jsx)(t.p,{children:"This list might work in most cases, but users might also want to customize the\nlist according to their preferences and domain they are working on. Starting\nwith this release it is now possible to define additional exclusions as well as\nremove some from the default list. This can be done via an additional Metals\nuser setting:"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{className:"language-json",children:'"metals.excluded-packages" : [\n  "akka.actor.typed.javadsl", "--javax"\n]\n'})}),"\n",(0,a.jsxs)(t.p,{children:[(0,a.jsx)(t.code,{children:"--"})," should only be used to remove some of the defaults if they aren't needed\nfor your project. Thanks to ",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"})," for\ncontributing this feature!"]}),"\n",(0,a.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,a.jsx)(t.p,{children:"Some of the smaller improvements to Metals include:"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"New recommended Maven Scala template."}),"\n",(0,a.jsx)(t.li,{children:"Fix go to source and auto imports in standalone worksheet files."}),"\n",(0,a.jsx)(t.li,{children:"Fix missing classes from _empty package in tree view."}),"\n",(0,a.jsx)(t.li,{children:"Add a command to quickly create a new worksheet with a default name."}),"\n"]}),"\n",(0,a.jsxs)(t.p,{children:["Beside all the core work on the server itself, there have also been a lot of\ngreat work done in various other Scalameta projects, which Metals relies on.\nThese projects include ",(0,a.jsx)(t.a,{href:"http://github.com/scalameta/mdoc",children:"mdoc"}),", which powers\nworksheets and ",(0,a.jsx)(t.a,{href:"http://github.com/scalameta/scalameta",children:"Scalameta"}),", which powers\nall SemanticDB and parsing powered features. Those contributions helped to\nunlock and improve Dotty support in Metals. So special thanks to those projects\nand contributors."]}),"\n",(0,a.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,a.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.9.3..v0.9.4\nScala Steward\nChris Kipp\nTomasz Godzik\nKrzysztof Bochenek\nEthan Atkins\nJoseph Price\n"})}),"\n",(0,a.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,a.jsxs)(t.h2,{id:"v094-2020-09-21",children:[(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.9.4",children:"v0.9.4"})," (2020-09-21)"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.9.3...v0.9.4",children:"Full Changelog"})}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["Bump other reference of coursier/cache\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2087",children:"#2087"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use built-in fetch depth and bump coursier cache\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2086",children:"#2086"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Refactor NewFileProvider\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2085",children:"#2085"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Strip out ",(0,a.jsx)(t.code,{children:"[E]"})," or ",(0,a.jsx)(t.code,{children:"[error]"})," from stacktrace in analyzer.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2083",children:"#2083"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update Bloop to 1.4.4-13-408f4d80\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2080",children:"#2080"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update mdoc version to 2.2.9\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2079",children:"#2079"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Change new-scala-file to enable quick creation of a file.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2075",children:"#2075"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-jmh to 0.4.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2071",children:"#2071"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update xnio-nio to 3.8.2.Final\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2067",children:"#2067"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalameta, semanticdb-scalac, ... to 4.3.22\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2070",children:"#2070"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-mdoc to 2.2.8\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2069",children:"#2069"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update mdoc-interfaces to 2.2.8\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2068",children:"#2068"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update undertow-core to 2.1.4.Final\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2066",children:"#2066"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update coursier to 2.0.0-RC6-26\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2065",children:"#2065"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-scalafix to 0.9.20\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2064",children:"#2064"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update bloop-config, bloop-launcher to 1.4.3-31-b16d7e50\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2063",children:"#2063"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix wrong definition position for worksheets\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2060",children:"#2060"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix typo in StringBloomFilter.scala\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2059",children:"#2059"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/joprice",children:"joprice"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add support for dotty 0.27.0-RC1 and update mdoc\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2058",children:"#2058"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't search for runTest code lenses in Ammonite scripts and worksheets.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2057",children:"#2057"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Look into ammonite flakiness in tests to see if we can remove it\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2054",children:"#2054"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add in new version of Ammonite\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2056",children:"#2056"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix missing classes from ",(0,a.jsx)(t.em,{children:"empty"})," package in treeView.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2053",children:"#2053"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Remove TravisCI stuff ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2055",children:"#2055"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Correct the way userConfig examples are displayed.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2045",children:"#2045"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Customizable package exclusions\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2012",children:"#2012"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update interface to 0.0.25\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2038",children:"#2038"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update emacs docs to include latest lsp-metals changes\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2033",children:"#2033"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpbochenek",children:"kpbochenek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalameta, semanticdb-scalac, ... to 4.3.21\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2042",children:"#2042"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-munit to 0.7.12\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2041",children:"#2041"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update jol-core to 0.13\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2040",children:"#2040"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 6.5.5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2039",children:"#2039"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update coursier to 2.0.0-RC6-25\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2037",children:"#2037"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update file-tree-views to 2.1.5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2036",children:"#2036"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update bloop-config, bloop-launcher to 1.4.3-27-dfdc9971\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2035",children:"#2035"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-dotty to 0.4.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2034",children:"#2034"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix auto imports and go to sources for worksheets outside sources\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2030",children:"#2030"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add Maven template to the curated list of templates\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2028",children:"#2028"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add support for dotty 0.26.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2027",children:"#2027"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Bump memory for test forks to 2GB\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2025",children:"#2025"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt docs to better explain how to manually install Metals prior to\n0.7.6 version ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2026",children:"#2026"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add parallel tests ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1985",children:"#1985"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Remove old gitignore and graal stuff.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2022",children:"#2022"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Clear fingerprints when semanticdb hash matches\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2021",children:"#2021"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Change link in blog post to absolute path\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2019",children:"#2019"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Switch file watching library to swoval\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2014",children:"#2014"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/eatkins",children:"eatkins"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Move releases to github actions\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2015",children:"#2015"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Analyze stacktrace ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1966",children:"#1966"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kpbochenek",children:"kpbochenek"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix typo in vscode docs\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2010",children:"#2010"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add release notes for 0.9.3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2008",children:"#2008"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,l.R)(),...e.components};return t?(0,a.jsx)(t,{...e,children:(0,a.jsx)(o,{...e})}):o(e)}},1020:(e,t,s)=>{var a=s(6540),l=Symbol.for("react.element"),n=Symbol.for("react.fragment"),i=Object.prototype.hasOwnProperty,c=a.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,r={key:!0,ref:!0,__self:!0,__source:!0};function h(e,t,s){var a,n={},h=null,o=null;for(a in void 0!==s&&(h=""+s),void 0!==t.key&&(h=""+t.key),void 0!==t.ref&&(o=t.ref),t)i.call(t,a)&&!r.hasOwnProperty(a)&&(n[a]=t[a]);if(e&&e.defaultProps)for(a in t=e.defaultProps)void 0===n[a]&&(n[a]=t[a]);return{$$typeof:l,type:e,key:h,ref:o,props:n,_owner:c.current}}t.Fragment=n,t.jsx=h,t.jsxs=h},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>i,x:()=>c});var a=s(6540);const l={},n=a.createContext(l);function i(e){const t=a.useContext(n);return a.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function c(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:i(e.components),a.createElement(n.Provider,{value:t},e.children)}}}]);