/*! For license information please see 2328fd63.d6694d76.js.LICENSE.txt */
"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[671],{1142:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>h,contentTitle:()=>n,default:()=>d,frontMatter:()=>i,metadata:()=>r,toc:()=>c});var a=s(4848),l=s(8453);const i={author:"Tomasz Godzik",title:"Metals v0.8.1 - Cobalt",authorURL:"https://twitter.com/TomekGodzik",authorImageURL:"https://github.com/tgodzik.png"},n=void 0,r={permalink:"/metals/blog/2020/02/26/cobalt",source:"@site/blog/2020-02-26-cobalt.md",title:"Metals v0.8.1 - Cobalt",description:"We are excited to announce the release of Metals v0.8.1. This release includes a",date:"2020-02-26T00:00:00.000Z",tags:[],readingTime:8.75,hasTruncateMarker:!1,authors:[{name:"Tomasz Godzik",url:"https://twitter.com/TomekGodzik",imageURL:"https://github.com/tgodzik.png",key:null,page:null}],frontMatter:{author:"Tomasz Godzik",title:"Metals v0.8.1 - Cobalt",authorURL:"https://twitter.com/TomekGodzik",authorImageURL:"https://github.com/tgodzik.png"},unlisted:!1,prevItem:{title:"Metals v0.8.3 - Cobalt",permalink:"/metals/blog/2020/03/19/cobalt"},nextItem:{title:"Metals v0.8.0 - Cobalt",permalink:"/metals/blog/2020/01/10/cobalt"}},h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Insert missing abstract members",id:"insert-missing-abstract-members",level:2},{value:"New file provider",id:"new-file-provider",level:2},{value:"Enable rename preview for Visual Studio Code",id:"enable-rename-preview-for-visual-studio-code",level:2},{value:"Debug in Emacs",id:"debug-in-emacs",level:2},{value:"Other changes",id:"other-changes",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.8.1 (2020-02-26)",id:"v081-2020-02-26",level:2}];function o(e){const t={a:"a",code:"code",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,l.R)(),...e.components};return(0,a.jsxs)(a.Fragment,{children:[(0,a.jsx)(t.p,{children:"We are excited to announce the release of Metals v0.8.1. This release includes a\nlarge number of bug fixes and some new features."}),"\n",(0,a.jsx)("table",{children:(0,a.jsxs)("tbody",{children:[(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Commits since last release"}),(0,a.jsx)("td",{align:"center",children:"313"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Merged PRs"}),(0,a.jsx)("td",{align:"center",children:"100"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Contributors"}),(0,a.jsx)("td",{align:"center",children:"19"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"Closed issues"}),(0,a.jsx)("td",{align:"center",children:"52"})]}),(0,a.jsxs)("tr",{children:[(0,a.jsx)("td",{children:"New features"}),(0,a.jsx)("td",{align:"center",children:"2"})]})]})}),"\n",(0,a.jsxs)(t.p,{children:["For full details: ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/19?closed=1",children:"https://github.com/scalameta/metals/milestone/19?closed=1"})]}),"\n",(0,a.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text, Atom and Eclipse. Metals is developed at the\n",(0,a.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,a.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,a.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,a.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,a.jsxs)(t.p,{children:["Check out ",(0,a.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"})," and\ngive Metals a try!"]}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"insert missing abstract members"}),"\n",(0,a.jsx)(t.li,{children:"new file provider"}),"\n",(0,a.jsx)(t.li,{children:"enable rename preview for Visual Studio Code"}),"\n",(0,a.jsx)(t.li,{children:"debug and run working in Emacs"}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"insert-missing-abstract-members",children:"Insert missing abstract members"}),"\n",(0,a.jsxs)(t.p,{children:["Thanks to yet again amazing work by\n",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),", we now support the quick fix code\naction for implementing abstract class members. This code action is available in\ncase of errors and works in the same way as the recent\n",(0,a.jsx)(t.a,{href:"https://scalameta.org/metals/blog/#completion-to-add-all-abstract-members",children:'"Implement all members"'}),"\ncompletion. It will add any missing abstract members at the top of the class\nwith the default implementation of ???."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://user-images.githubusercontent.com/9353584/75405616-81047f00-5951-11ea-9972-a12e25b1746a.gif",alt:"action"})}),"\n",(0,a.jsx)(t.h2,{id:"new-file-provider",children:"New file provider"}),"\n",(0,a.jsxs)(t.p,{children:["In the last release we added support for worksheets, which are an easy way to\nquickly evaluate some code. To create a new worksheet you need to create a file\nwith the extension ",(0,a.jsx)(t.code,{children:".worksheet.sc"}),", which was not that easy to figure out. To\nfix that situation, thanks to\n",(0,a.jsx)(t.a,{href:"https://github.com/alekseiAlefirov",children:"alekseiAlefirov"}),", we now have a menu to\ncreate different types of new files including classes, traits, object, package\nobjects and of course worksheets. At this time it's only available in the Visual\nStudio Code and ",(0,a.jsx)(t.code,{children:"coc-metals"})," extensions."]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.img,{src:"https://user-images.githubusercontent.com/10850363/73563467-adfe6880-445d-11ea-89f6-a9a6398034da.gif",alt:"new-file-provider"})}),"\n",(0,a.jsxs)(t.p,{children:["You can also just use the command ",(0,a.jsx)(t.code,{children:"Metals: New Scala File"}),", which will use the\ncurrent file's directory and create the new file there. And additionally, when\nusing a relative path like ",(0,a.jsx)(t.code,{children:"a/b/C"})," Metals will create all needed directories and\nadd a proper package declaration to the new file."]}),"\n",(0,a.jsx)(t.h2,{id:"enable-rename-preview-for-visual-studio-code",children:"Enable rename preview for Visual Studio Code"}),"\n",(0,a.jsxs)(t.p,{children:["Rename previews were added in the last Visual Studio Code\n",(0,a.jsx)(t.a,{href:"https://code.visualstudio.com/updates/v1_42#_rename-preview",children:"release"}),", which\nenable users to see what will be changed when a rename is executed. Due to\noptimization and UX reasons Metals renamed symbols in closed files in the\nbackground without informing the editor. However, this also causes the previews\nto be misleading, since they might not be complete."]}),"\n",(0,a.jsx)(t.p,{children:"To fix that situation we now send all the files to VS Code, which then is able\nto display the preview. We do it, however, for up to 300 files, which threshold\nwas experimentally estimated. When the number of files reaches over that number,\nwe revert to the old behavior of not opening closed files. The reason for this\nis that in some cases for larger numbers of files the editor could hang and\nbecome unresponsive."}),"\n",(0,a.jsx)(t.h2,{id:"debug-in-emacs",children:"Debug in Emacs"}),"\n",(0,a.jsxs)(t.p,{children:["In the last release run and debug support was only available for Visual Studio\nCode, but thanks to amazing work by the contributors it is now possible to use\nit in Emacs via ",(0,a.jsx)(t.a,{href:"https://github.com/emacs-lsp/lsp-mode",children:"lsp-mode's"})," metals\nsettings and dap-mode."]}),"\n",(0,a.jsx)(t.h2,{id:"other-changes",children:"Other changes"}),"\n",(0,a.jsx)(t.p,{children:"This release includes mostly fixes and minor features, which is due to the large\nscope of the last one. We concentrated this last month on stability and making\nsure everything is up to scratch. Those minor changes included:"}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsx)(t.li,{children:"use bloop.export-jar-classifiers property when importing the build from sbt"}),"\n",(0,a.jsx)(t.li,{children:"ignore bad compiler options passed by users in order not to break completions"}),"\n",(0,a.jsx)(t.li,{children:"add an override for the Bloop plugin version and promote it to LSP settings"}),"\n",(0,a.jsx)(t.li,{children:"fix onTypeFormatting when used in top level multi-line strings"}),"\n",(0,a.jsxs)(t.li,{children:["fix issues when ",(0,a.jsx)(t.code,{children:"go to implementation"})," resolves local symbols"]}),"\n",(0,a.jsx)(t.li,{children:"change the non-fatal jar error to debug rather than log warning"}),"\n",(0,a.jsx)(t.li,{children:"add support for worksheets without a build target by defaulting to Metals\nScala version"}),"\n",(0,a.jsx)(t.li,{children:"rename file only if the renamed symbol is directly enclosed by a package"}),"\n",(0,a.jsx)(t.li,{children:"fix exhaustive match completion on Java enums"}),"\n",(0,a.jsx)(t.li,{children:"use workspace level Gradle wrapper if present for bloopInstall"}),"\n",(0,a.jsx)(t.li,{children:"fix onTypeFormatting issue with multiple pipes on a line"}),"\n",(0,a.jsx)(t.li,{children:"fix rename issues with backticks"}),"\n",(0,a.jsx)(t.li,{children:"add documentation on how to use proxies and mirrors"}),"\n"]}),"\n",(0,a.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,a.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release!"}),"\n",(0,a.jsx)(t.p,{children:"Again, we have some new contributors and a lot of returning ones!"}),"\n",(0,a.jsx)(t.pre,{children:(0,a.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.8.0..v0.8.1\nOlafur Pall Geirsson\nTomasz Godzik\nScala Steward\nChris Kipp\nGabriele Petronella\nRikito Taniguchi\nAleksei Alefirov\nJakub Koz\u0142owski\nArthur McGibbon\nCarlos Rodriguez Guette\nDanil Bykov\nJakob Odersky\nJoris\nMartin Duhem\nRuslans Tarasovs\nWin Wang\njoriscode\nKei Sunagawa\nZainab Ali\n"})}),"\n",(0,a.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,a.jsxs)(t.h2,{id:"v081-2020-02-26",children:[(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.8.1",children:"v0.8.1"})," (2020-02-26)"]}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.8.0...v0.8.1",children:"Full Changelog"})}),"\n",(0,a.jsx)(t.p,{children:(0,a.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,a.jsxs)(t.ul,{children:["\n",(0,a.jsxs)(t.li,{children:["add openFilesOnRenameProvider to experimental\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1463",children:"#1463"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add new LSP extension 'metals/pickInput' to implement the \"Create Scala file\"\ncommand ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1447",children:"#1447"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/alekseiAlefirov",children:"alekseiAlefirov"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Restart the Bloop server for Pants users if it's running a known old version\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1460",children:"#1460"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Polishing touches on Pants integration\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1459",children:"#1459"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix issues discovered while testing out IntelliJ integration.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1457",children:"#1457"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["add in checkmark for debug on emacs\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1458",children:"#1458"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Make use of latest Pants and Bloop improvements\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1452",children:"#1452"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["add properties back in ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1455",children:"#1455"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Record visited Scala files to make sure they are not duplicated\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1451",children:"#1451"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Move some properties to clientExperimentalCapabilities\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1414",children:"#1414"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use proper range for references with backticks\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1430",children:"#1430"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Make sure we properly handle overflow events even if the path is null\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1435",children:"#1435"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:['Rename "link" subcommand to "switch"\n',(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1446",children:"#1446"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix typo: s/lastVisistedParentTrees/lastVisitedParentTrees/\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1444",children:"#1444"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-munit to 0.5.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1443",children:"#1443"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-munit to 0.5.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1442",children:"#1442"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add missing config in Eglot\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1441",children:"#1441"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/carlosrogue",children:"carlosrogue"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalafmt-dynamic to 2.4.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1440",children:"#1440"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Mark most often failing tests as flaky and fix DefinitionLspSuite test\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1439",children:"#1439"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update scalafmt-dynamic to 2.4.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1437",children:"#1437"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix download URL for Coursier command-line interface\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1436",children:"#1436"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/keiSunagawa",children:"keiSunagawa"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Document emacs integration with debug adapter and tree view protocol\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1438",children:"#1438"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/zainab-ali",children:"zainab-ali"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update bloop-config, bloop-launcher to 1.4.0-RC1-62-d098adda\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1434",children:"#1434"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Refactor: split Completions.scala into smaller files\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1423",children:"#1423"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update bloop-config, bloop-launcher to 1.4.0-RC1-56-a2040035\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1429",children:"#1429"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix issue with multiple pipes in string\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1427",children:"#1427"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Return all rename file changes up to a threshold\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1405",children:"#1405"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 6.2.3\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1426",children:"#1426"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update metaconfig-core to 0.9.8\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1425",children:"#1425"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Send stackTraces as paths rather than URIs\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1418",children:"#1418"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/danilbykov",children:"danilbykov"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Revamp the BloopPants command-line interface.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1420",children:"#1420"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update ujson to 0.9.9 ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1419",children:"#1419"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["CodeAction: insert missing abstract members\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1379",children:"#1379"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Don't insert parentheses when importing\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1284",children:"#1284"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kubukoz",children:"kubukoz"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Change config options to match coc\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1390",children:"#1390"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use workspace level Gradle wrapper if present\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1412",children:"#1412"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Arthurm1",children:"Arthurm1"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt-ci-release to 1.5.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1408",children:"#1408"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update jsoup to 1.12.2 ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1410",children:"#1410"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Remove check for focused document on non-supporting editors\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1407",children:"#1407"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Added Command to create new worksheet\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1339",children:"#1339"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/alekseiAlefirov",children:"alekseiAlefirov"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add in tree view protocol docs for vim\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1392",children:"#1392"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Improve logic to merge Pants targets.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1400",children:"#1400"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update pprint to 0.5.9 ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1403",children:"#1403"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 6.2.2\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1401",children:"#1401"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Do not accept empty string values from the lsp settings\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1397",children:"#1397"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Enable verbose sbt loggers in CI.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1399",children:"#1399"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix exhaustive match completion on Java enums\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1393",children:"#1393"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/Duhemm",children:"Duhemm"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update munit, sbt-munit to 0.4.5\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1395",children:"#1395"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add .scalafmt.conf symbolic link when exporting Pants build.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1386",children:"#1386"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Avoid long filename for Pants output file.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1385",children:"#1385"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update interface to 0.0.18\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1383",children:"#1383"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt, scripted-plugin to 1.3.8\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1384",children:"#1384"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Rename file only if renamed symbol is directly enclosed by a package (closes\n#1380) ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1382",children:"#1382"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kubukoz",children:"kubukoz"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Several improvements to Pants integration\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1375",children:"#1375"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Include ScalaBuildTarget in ScalaTarget\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1374",children:"#1374"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Upgrade bloop-launcher to nightly version of Bloop.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1371",children:"#1371"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Mark flaky tests as flaky.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1373",children:"#1373"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 6.2.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1372",children:"#1372"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Upgrade to the latest junit-interface.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1368",children:"#1368"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),") Cannot read PR 1352"]}),"\n",(0,a.jsxs)(t.li,{children:["Add support for rambo worksheets without build target\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1364",children:"#1364"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add documentation for using proxy and mirrors\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1356",children:"#1356"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Merge pull request #1363 from olafurpg/pants-resources\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1363",children:"#1363"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),") Cannot read PR 1361"]}),"\n",(0,a.jsxs)(t.li,{children:["Start recording test reports with sbt-munit\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1360",children:"#1360"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update munit to 0.4.3 ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1358",children:"#1358"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix testAsync -> test after a logical merge conflict\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1357",children:"#1357"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Do not accept synthetic symbol with matching qualifier\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1338",children:"#1338"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Replace utest with MUnit\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1277",children:"#1277"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update interface to 0.0.17\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1355",children:"#1355"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update jol-core to 0.10\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1351",children:"#1351"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update nuprocess to 1.2.6\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1350",children:"#1350"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Several Pants fixes ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1349",children:"#1349"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Copy jars from Pants export-classpath into Bloop directory.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1348",children:"#1348"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Resolve uri from path properly for setting breakpoints\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1346",children:"#1346"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update Nu Process to 1.2.5 and directory watcher to 0.9.9\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1340",children:"#1340"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update flyway-core to 6.2.0\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1344",children:"#1344"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:['Updated "unsupported features"\n',(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1343",children:"#1343"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/rtar",children:"rtar"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["fix logic for detected if bloop-sbt is already installed\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1342",children:"#1342"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add caching to github actions for Cousier\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1276",children:"#1276"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Mention debugging in contributing docs\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1337",children:"#1337"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/kubukoz",children:"kubukoz"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Make sure the path on windows is a proper URI\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1335",children:"#1335"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Change last require in PC to warning\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1333",children:"#1333"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Change the non-fatal jar error to debug rather than log warning\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1331",children:"#1331"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix issues when go to implementation resolves local symbols\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1330",children:"#1330"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use actual mill version in predef\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1307",children:"#1307"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/jodersky",children:"jodersky"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update recommended mappings with the latest\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1289",children:"#1289"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add in fix for onTypeFormatting on top level multi-line strings\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1329",children:"#1329"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update lz4-java to 1.7.1\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1327",children:"#1327"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add an override for build tool plugin version and promote LSP settings\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1310",children:"#1310"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Change require to warning for the presentation compiler\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1324",children:"#1324"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix version of sbt-metals for pre 0.8.0 instructions\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1323",children:"#1323"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update sbt, scripted-plugin to 1.3.7\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1319",children:"#1319"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Typo ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1318",children:"#1318"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/joriscode",children:"joriscode"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Fix worksheet gif for cobalt release notes to show proper extension\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1314",children:"#1314"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update versions in issue templates\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1312",children:"#1312"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Use bloop.export-jar-classifiers property\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1212",children:"#1212"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/joriscode",children:"joriscode"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update screen-record of Scaladoc auto-completion in v0.8.0 release-note.\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1309",children:"#1309"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Update ujson to 0.9.8 ",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1299",children:"#1299"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Link authors' github accounts for release notes\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1295",children:"#1295"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Remove nix publishing and add a reminder to update version\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1294",children:"#1294"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,a.jsxs)(t.li,{children:["Add release notes for the epic version of Metals ",":D","\n",(0,a.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1278",children:"#1278"}),"\n(",(0,a.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,l.R)(),...e.components};return t?(0,a.jsx)(t,{...e,children:(0,a.jsx)(o,{...e})}):o(e)}},1020:(e,t,s)=>{var a=s(6540),l=Symbol.for("react.element"),i=Symbol.for("react.fragment"),n=Object.prototype.hasOwnProperty,r=a.__SECRET_INTERNALS_DO_NOT_USE_OR_YOU_WILL_BE_FIRED.ReactCurrentOwner,h={key:!0,ref:!0,__self:!0,__source:!0};function c(e,t,s){var a,i={},c=null,o=null;for(a in void 0!==s&&(c=""+s),void 0!==t.key&&(c=""+t.key),void 0!==t.ref&&(o=t.ref),t)n.call(t,a)&&!h.hasOwnProperty(a)&&(i[a]=t[a]);if(e&&e.defaultProps)for(a in t=e.defaultProps)void 0===i[a]&&(i[a]=t[a]);return{$$typeof:l,type:e,key:c,ref:o,props:i,_owner:r.current}}t.Fragment=i,t.jsx=c,t.jsxs=c},4848:(e,t,s)=>{e.exports=s(1020)},8453:(e,t,s)=>{s.d(t,{R:()=>n,x:()=>r});var a=s(6540);const l={},i=a.createContext(l);function n(e){const t=a.useContext(i);return a.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:n(e.components),a.createElement(i.Provider,{value:t},e.children)}}}]);