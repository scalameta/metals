"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[9026],{7121:(e,t,s)=>{s.r(t),s.d(t,{assets:()=>h,contentTitle:()=>a,default:()=>d,frontMatter:()=>i,metadata:()=>r,toc:()=>c});var n=s(5893),l=s(1151);const i={author:"Tomasz Godzik",title:"Metals v0.8.3 - Cobalt",authorURL:"https://twitter.com/TomekGodzik",authorImageURL:"https://github.com/tgodzik.png"},a=void 0,r={permalink:"/metals/blog/2020/03/19/cobalt",source:"@site/blog/2020-03-19-cobalt.md",title:"Metals v0.8.3 - Cobalt",description:"We are happy to announce the release of Metals v0.8.3, which main purpose is",date:"2020-03-19T00:00:00.000Z",formattedDate:"March 19, 2020",tags:[],readingTime:2.74,hasTruncateMarker:!1,authors:[{name:"Tomasz Godzik",url:"https://twitter.com/TomekGodzik",imageURL:"https://github.com/tgodzik.png"}],frontMatter:{author:"Tomasz Godzik",title:"Metals v0.8.3 - Cobalt",authorURL:"https://twitter.com/TomekGodzik",authorImageURL:"https://github.com/tgodzik.png"},unlisted:!1,prevItem:{title:"Metals v0.8.4 - Cobalt",permalink:"/metals/blog/2020/04/10/cobalt"},nextItem:{title:"Metals v0.8.1 - Cobalt",permalink:"/metals/blog/2020/02/26/cobalt"}},h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Miscellaneous improvements",id:"miscellaneous-improvements",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.8.3 (2020-03-20)",id:"v083-2020-03-20",level:2}];function o(e){const t={a:"a",code:"code",h2:"h2",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,l.a)(),...e.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(t.p,{children:"We are happy to announce the release of Metals v0.8.3, which main purpose is\nadding support for the new Scala version, 2.12.11. Additionally, we included a\ncouple of recent fixes."}),"\n",(0,n.jsx)("table",{children:(0,n.jsxs)("tbody",{children:[(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Commits since last release"}),(0,n.jsx)("td",{align:"center",children:"61"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Merged PRs"}),(0,n.jsx)("td",{align:"center",children:"18"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Contributors"}),(0,n.jsx)("td",{align:"center",children:"9"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"Closed issues"}),(0,n.jsx)("td",{align:"center",children:"14"})]}),(0,n.jsxs)("tr",{children:[(0,n.jsx)("td",{children:"New features"}),(0,n.jsx)("td",{align:"center",children:"1"})]})]})}),"\n",(0,n.jsxs)(t.p,{children:["For full details: ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/21?closed=1",children:"https://github.com/scalameta/metals/milestone/21?closed=1"})]}),"\n",(0,n.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text, Atom and Eclipse. Metals is developed at the\n",(0,n.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,n.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,n.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,n.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,n.jsxs)(t.p,{children:["Check out ",(0,n.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"support for Scala 2.12.11"}),"\n",(0,n.jsx)(t.li,{children:"recent minor improvements"}),"\n"]}),"\n",(0,n.jsx)(t.h2,{id:"miscellaneous-improvements",children:"Miscellaneous improvements"}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsx)(t.li,{children:"fix bug where worksheets got stuck evaluating forever"}),"\n",(0,n.jsx)(t.li,{children:"fix issue where Metals would incorrectly prompt about a Bloop version change"}),"\n",(0,n.jsx)(t.li,{children:"fix a bug where rename symbol produced invalid code for class hierarchies\nusing generics"}),"\n",(0,n.jsx)(t.li,{children:"ignore return type when renaming overriden methods and fields"}),"\n",(0,n.jsx)(t.li,{children:"fix bug where docstrings for workspace sources returned stale documentation"}),"\n",(0,n.jsx)(t.li,{children:"goto definition now works for standalone source files, even if the build has\nnot been imported"}),"\n",(0,n.jsx)(t.li,{children:"fix issue with string alignment when multiple multiline strings were present\nin a file"}),"\n"]}),"\n",(0,n.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,n.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release!"}),"\n",(0,n.jsx)(t.pre,{children:(0,n.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.8.1..v0.8.3\nTomasz Godzik\nOlafur Pall Geirsson\nChris Kipp\nKrzysztof Bochenek\nTomasz Pasternak\nWin Wang\n\u0141ukasz Wawrzyk\nLorenzo Gabriele\nRikito Taniguchi\n"})}),"\n",(0,n.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,n.jsxs)(t.h2,{id:"v083-2020-03-20",children:[(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.8.3",children:"v0.8.3"})," (2020-03-20)"]}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.8.1...v0.8.3",children:"Full Changelog"})}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,n.jsxs)(t.ul,{children:["\n",(0,n.jsxs)(t.li,{children:["Undeprecate 2.12.10 ",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1517",children:"#1517"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Always launch IntelliJ in the project's root directory\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1516",children:"#1516"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tpasternak",children:"tpasternak"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Generate bloop.settings.json with project refresh command\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1506",children:"#1506"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/lukaszwawrzyk",children:"lukaszwawrzyk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add release notes for v0.8.2 and bump versions\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1515",children:"#1515"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Do not include scala boot library\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1507",children:"#1507"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Use a custom IDEA launcher under a new flag.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1513",children:"#1513"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add support for Scala 2.12.11\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1510",children:"#1510"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Only check relevant text for default indent\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1505",children:"#1505"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Export packagePrefix to bloop\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1470",children:"#1470"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/lukaszwawrzyk",children:"lukaszwawrzyk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update scalameta to 4.3.4 and fix existing tests\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1499",children:"#1499"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add helper to pre-download Metals dependencies.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1501",children:"#1501"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add in new scalafmt default version\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1504",children:"#1504"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Do not generate synthetic modules\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1500",children:"#1500"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tpasternak",children:"tpasternak"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix Bloop sending a restart message when custom version of Bloop is used\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1491",children:"#1491"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["New ",(0,n.jsx)(t.code,{children:"create"})," option:--no-root-project option\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1490",children:"#1490"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tpasternak",children:"tpasternak"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Handle fatal exceptions when evaluating worksheets.\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1498",children:"#1498"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/olafurpg",children:"olafurpg"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Update github actions to checkout@v2\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1492",children:"#1492"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Skip coursier download in fastpass create with --coursier-binary option\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1486",children:"#1486"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/wiwa",children:"wiwa"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Don't delete bloop.settings.json when exporting Pants builds\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1488",children:"#1488"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/lukaszwawrzyk",children:"lukaszwawrzyk"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add ability to specify zipkin url for fastpass\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1481",children:"#1481"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/wiwa",children:"wiwa"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["During rename matching methods ignore return type\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1485",children:"#1485"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpbochenek",children:"kpbochenek"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix rename for hierarchy with generics\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1484",children:"#1484"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/kpbochenek",children:"kpbochenek"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Bump mill default version\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1480",children:"#1480"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/lolgab",children:"lolgab"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add in math max when looking for lastIndex\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1479",children:"#1479"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add in new statusline info from latest release\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1476",children:"#1476"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Expire docstrings' cache on save\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1473",children:"#1473"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tanishiking",children:"tanishiking"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Remove properties that are no longer needed\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1475",children:"#1475"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Fix issue when warning about Bloop version change\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1472",children:"#1472"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,n.jsxs)(t.li,{children:["Add release notes for the 0.8.1 release\n",(0,n.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/1468",children:"#1468"}),"\n(",(0,n.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){const{wrapper:t}={...(0,l.a)(),...e.components};return t?(0,n.jsx)(t,{...e,children:(0,n.jsx)(o,{...e})}):o(e)}},1151:(e,t,s)=>{s.d(t,{Z:()=>r,a:()=>a});var n=s(7294);const l={},i=n.createContext(l);function a(e){const t=n.useContext(i);return n.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(l):e.components||l:a(e.components),n.createElement(i.Provider,{value:t},e.children)}}}]);