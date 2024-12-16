"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["6255"],{21679:function(e,t,s){s.r(t),s.d(t,{assets:function(){return h},contentTitle:function(){return r},default:function(){return d},frontMatter:function(){return l},metadata:function(){return n},toc:function(){return c}});var n=s(39363),i=s(85893),a=s(50065);let l={authors:"tgodzik",title:"Metals v0.9.6 - Lithium"},r=void 0,h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Show implicit conversions and classes",id:"show-implicit-conversions-and-classes",level:2},{value:"Navigating stacktrace",id:"navigating-stacktrace",level:2},{value:"Troubleshooting",id:"troubleshooting",level:2},{value:"Miscellaneous",id:"miscellaneous",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.9.6 (2020-11-20)",id:"v096-2020-11-20",level:2}];function o(e){let t={a:"a",code:"code",h2:"h2",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,a.a)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.9.6, which mostly concentrates\non adding support for the newly released Scala 2.13.4, but also adds some\nimprovements for already existing features."}),"\n",(0,i.jsx)("table",{children:(0,i.jsxs)("tbody",{children:[(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Commits since last release"}),(0,i.jsx)("td",{align:"center",children:"59"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Merged PRs"}),(0,i.jsx)("td",{align:"center",children:"31"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Contributors"}),(0,i.jsx)("td",{align:"center",children:"4"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"Closed issues"}),(0,i.jsx)("td",{align:"center",children:"2"})]}),(0,i.jsxs)("tr",{children:[(0,i.jsx)("td",{children:"New features"}),(0,i.jsx)("td",{align:"center",children:"2"})]})]})}),"\n",(0,i.jsxs)(t.p,{children:["For full details: ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/29?closed=1",children:"https://github.com/scalameta/metals/milestone/29?closed=1"})]}),"\n",(0,i.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text, Atom and Eclipse. Metals is developed at the\n",(0,i.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,i.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,i.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,i.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,i.jsxs)(t.p,{children:["Check out ",(0,i.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsx)(t.li,{children:"Scala 2.13.4 support."}),"\n",(0,i.jsx)(t.li,{children:"Show implicit conversions and classes."}),"\n",(0,i.jsx)(t.li,{children:"Navigating stacktrace in debug console."}),"\n",(0,i.jsx)(t.li,{children:"New troubleshooting page."}),"\n"]}),"\n",(0,i.jsx)(t.h2,{id:"show-implicit-conversions-and-classes",children:"Show implicit conversions and classes"}),"\n",(0,i.jsxs)(t.p,{children:["Last\n",(0,i.jsx)(t.a,{href:"/metals/blog/2020/11/10/lithium#show-implicits-and-type-decorations",children:"release"}),"\nintroduced the option to display additional data inferred from the code to a\nuser. Starting with this release it's also possible to show implicit conversions\nand classes, which can be enabled by the additional\n",(0,i.jsx)(t.code,{children:"showImplicitConversionsAndClasses"})," setting. Moreover, Visual Studio Code\nextension has 3 new commands to toggle all these new options, which enables\nusers to quickly peek at the additional information about their code and then\nturn it back off. All these commands can be bound to a shortcut to further\nimprove the user experience."]}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://i.imgur.com/k6GRgue.gif",alt:"implicits"})}),"\n",(0,i.jsx)(t.p,{children:"Any editor that allows for quickly changing these settings will also benefit\nfrom this change, as the file's decorations are refreshed instantly upon\nchanging any of those settings."}),"\n",(0,i.jsx)(t.h2,{id:"navigating-stacktrace",children:"Navigating stacktrace"}),"\n",(0,i.jsxs)(t.p,{children:["Previously, it was possible to navigate a stacktrace using the\n",(0,i.jsx)(t.code,{children:"Analyze stacktrace"})," command which was added in the\n",(0,i.jsx)(t.a,{href:"/metals/blog/2020/09/21/lithium#analyze-stacktrace-command",children:"v0.9.4"})," release. It\nturns out, we can reuse the same mechanism to show file links in the\n",(0,i.jsx)(t.code,{children:"Debug Console"})," in Visual Studio code:"]}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:"https://i.imgur.com/qeitymN.gif",alt:"navigate-stacktrace"})}),"\n",(0,i.jsx)(t.p,{children:"This was achieved by adding additional information to the output already sent to\nthe editor, so this additional file links should also be reused by any other DAP\nclients."}),"\n",(0,i.jsx)(t.h2,{id:"troubleshooting",children:"Troubleshooting"}),"\n",(0,i.jsxs)(t.p,{children:["As an addition to these new features, we've also recently added a new\n",(0,i.jsx)(t.a,{href:"/metals/docs/troubleshooting/faq",children:"troubleshooting page"})," that should answer the\nmost basic questions you can have. As the page is fairly new we would appreciate\nany help in improving it so let us know if you feel anything is missing."]}),"\n",(0,i.jsx)(t.h2,{id:"miscellaneous",children:"Miscellaneous"}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsx)(t.li,{children:"Fix continuous compilation when opening tree view in sbt BSP"}),"\n",(0,i.jsx)(t.li,{children:"Add 0.27.0-RC1 back to supported versions"}),"\n"]}),"\n",(0,i.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,i.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,i.jsx)(t.pre,{children:(0,i.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.9.5..v0.9.6\nTomasz Godzik\nScala Steward\nChris Kipp\nGabriele Petronella\nKrzysiek Bochenek\ndependabot[bot]\n"})}),"\n",(0,i.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,i.jsxs)(t.h2,{id:"v096-2020-11-20",children:[(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.9.6",children:"v0.9.6"})," (2020-11-20)"]}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.9.5...v0.9.6",children:"Full Changelog"})}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:["Refresh synthetics when user settings change\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2246",children:"#2246"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update scalameta to 4.4.0 and add support for Scala 2.13.4\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2247",children:"#2247"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add source and line to debug output in case of stack traces\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2243",children:"#2243"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix continuous compilation when opening tree view in sbt BSP\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2242",children:"#2242"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add option to show implicit conversions as decorations\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2232",children:"#2232"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Bump olafurpg/setup-gpg from v2 to v3\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2240",children:"#2240"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/dependabot%5Bbot%5D",children:"dependabot[bot]"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Move dependabot config file to right location\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2239",children:"#2239"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Configure dependabot to update github-actions\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2237",children:"#2237"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/gabro",children:"gabro"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Reorganize docs and add in FAQ.\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2234",children:"#2234"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update scribe, scribe-slf4j to 3.0.4\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2222",children:"#2222"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update org.eclipse.lsp4j, ... to 0.10.0\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2230",children:"#2230"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/kpbochenek",children:"kpbochenek"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update munit-docs, sbt-munit to 0.7.17\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2231",children:"#2231"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/kpbochenek",children:"kpbochenek"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update sbt-ci-release to 1.5.4\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2221",children:"#2221"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update bloop-config, bloop-launcher to 1.4.5-6-4768184c\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2220",children:"#2220"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update sbt-dotty to 0.4.6\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2219",children:"#2219"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update coursier to 2.0.6\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2223",children:"#2223"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update munit to 0.7.17 ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2228",children:"#2228"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update mdoc-interfaces to 2.2.12\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2227",children:"#2227"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update mdoc, sbt-mdoc to 2.2.12\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2226",children:"#2226"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update flyway-core to 7.2.0\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2225",children:"#2225"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/scala-steward",children:"scala-steward"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add 0.27.0-RC1 back to supported versions\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2213",children:"#2213"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add additional newline to fix problems with markdown\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2215",children:"#2215"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add additional troubleshooting section for mirrors\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2211",children:"#2211"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add testing for DownloadDependencies\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2206",children:"#2206"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update docs to mention ",(0,i.jsx)(t.code,{children:"inlineDecorationProvider"}),"\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2209",children:"#2209"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix release notes to mention emacs\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2205",children:"#2205"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Fix scala3Dependency for various binary scala3/dotty versions\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2203",children:"#2203"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Update release docs ",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2199",children:"#2199"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Correct typos in blog and Doctor\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2202",children:"#2202"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add release notes for 0.9.5\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2197",children:"#2197"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,i.jsxs)(t.li,{children:["Add in blog post about sbt BSP\n",(0,i.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2193",children:"#2193"}),"\n(",(0,i.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,a.a)(),...e.components};return t?(0,i.jsx)(t,{...e,children:(0,i.jsx)(o,{...e})}):o(e)}},50065:function(e,t,s){s.d(t,{Z:function(){return r},a:function(){return l}});var n=s(67294);let i={},a=n.createContext(i);function l(e){let t=n.useContext(a);return n.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(i):e.components||i:l(e.components),n.createElement(a.Provider,{value:t},e.children)}},39363:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2020/11/20/lithium","source":"@site/blog/2020-11-20-lithium.md","title":"Metals v0.9.6 - Lithium","description":"We\'re happy to announce the release of Metals v0.9.6, which mostly concentrates","date":"2020-11-20T00:00:00.000Z","tags":[],"readingTime":3.625,"hasTruncateMarker":false,"authors":[{"name":"Tomasz Godzik","url":"https://twitter.com/TomekGodzik","imageURL":"https://github.com/tgodzik.png","key":"tgodzik","page":null}],"frontMatter":{"authors":"tgodzik","title":"Metals v0.9.6 - Lithium"},"unlisted":false,"prevItem":{"title":"Metals v0.9.7 - Lithium","permalink":"/metals/blog/2020/11/26/lithium"},"nextItem":{"title":"Metals v0.9.5 - Lithium","permalink":"/metals/blog/2020/11/10/lithium"}}')}}]);