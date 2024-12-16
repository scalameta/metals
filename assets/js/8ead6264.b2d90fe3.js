"use strict";(self.webpackChunk=self.webpackChunk||[]).push([["2158"],{5643:function(e,t,s){s.r(t),s.d(t,{assets:function(){return h},contentTitle:function(){return a},default:function(){return d},frontMatter:function(){return i},metadata:function(){return n},toc:function(){return c}});var n=s(87164),r=s(85893),l=s(50065);let i={authors:"dos65",title:"Metals v0.10.4 - Tungsten"},a=void 0,h={authorsImageUrls:[void 0]},c=[{value:"TL;DR",id:"tldr",level:2},{value:"Contributors",id:"contributors",level:2},{value:"Merged PRs",id:"merged-prs",level:2},{value:"v0.10.4 (2021-05-31)",id:"v0104-2021-05-31",level:2}];function o(e){let t={a:"a",code:"code",h2:"h2",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,l.a)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsx)(t.p,{children:"We're happy to announce the release of Metals v0.10.4, which adds support for\nScala 2.12.14."}),"\n",(0,r.jsx)("table",{children:(0,r.jsxs)("tbody",{children:[(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Commits since last release"}),(0,r.jsx)("td",{align:"center",children:"24"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Merged PRs"}),(0,r.jsx)("td",{align:"center",children:"11"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Contributors"}),(0,r.jsx)("td",{align:"center",children:"4"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"Closed issues"}),(0,r.jsx)("td",{align:"center",children:"3"})]}),(0,r.jsxs)("tr",{children:[(0,r.jsx)("td",{children:"New features"}),(0,r.jsx)("td",{align:"center",children:"1"})]})]})}),"\n",(0,r.jsxs)(t.p,{children:["For full details: ",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/milestone/39?closed=1",children:"https://github.com/scalameta/metals/milestone/39?closed=1"})]}),"\n",(0,r.jsxs)(t.p,{children:["Metals is a language server for Scala that works with VS Code, Vim, Emacs,\nSublime Text and Eclipse. Metals is developed at the\n",(0,r.jsx)(t.a,{href:"https://scala.epfl.ch/",children:"Scala Center"})," and ",(0,r.jsx)(t.a,{href:"https://virtuslab.com",children:"VirtusLab"}),"\nwith the help from ",(0,r.jsx)(t.a,{href:"https://lunatech.com",children:"Lunatech"})," along with contributors from\nthe community."]}),"\n",(0,r.jsx)(t.h2,{id:"tldr",children:"TL;DR"}),"\n",(0,r.jsxs)(t.p,{children:["Check out ",(0,r.jsx)(t.a,{href:"https://scalameta.org/metals/",children:"https://scalameta.org/metals/"}),", and\ngive Metals a try!"]}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsx)(t.li,{children:"Add support for Scala 2.12.14"}),"\n"]}),"\n",(0,r.jsx)(t.h2,{id:"contributors",children:"Contributors"}),"\n",(0,r.jsx)(t.p,{children:"Big thanks to everybody who contributed to this release or reported an issue!"}),"\n",(0,r.jsx)(t.pre,{children:(0,r.jsx)(t.code,{children:"$ git shortlog -sn --no-merges v0.10.3..v0.10.4\nTomasz Godzik\nVadim Chelyshov\nLuigi Frunzio\nChris Kipp\n"})}),"\n",(0,r.jsx)(t.h2,{id:"merged-prs",children:"Merged PRs"}),"\n",(0,r.jsxs)(t.h2,{id:"v0104-2021-05-31",children:[(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/tree/v0.10.4",children:"v0.10.4"})," (2021-05-31)"]}),"\n",(0,r.jsx)(t.p,{children:(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/compare/v0.10.3...v0.10.4",children:"Full Changelog"})}),"\n",(0,r.jsx)(t.p,{children:(0,r.jsx)(t.strong,{children:"Merged pull requests:"})}),"\n",(0,r.jsxs)(t.ul,{children:["\n",(0,r.jsxs)(t.li,{children:["Add support for 2.12.14\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2826",children:"#2826"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Fix pprint dependency for Scala3 RC versions\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2824",children:"#2824"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/dos65",children:"dos65"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Print better types on hover\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2821",children:"#2821"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Don't warn about sourceroot for Scala 3\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2819",children:"#2819"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/ckipp01",children:"ckipp01"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Update pprint to newest version\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2818",children:"#2818"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Fix issues when importing in an empty package\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2817",children:"#2817"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Fix error for remote debugging when missing build target\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2812",children:"#2812"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/Giggiux",children:"Giggiux"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Publish scalameta parser diagnostics for Scala 3\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2807",children:"#2807"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Group Scala versions in docs to make it easier to see which are supported\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2806",children:"#2806"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Fix duplicate toplevels object definition when using enums\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2809",children:"#2809"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n",(0,r.jsxs)(t.li,{children:["Add release notes for v0.10.3 release\n",(0,r.jsx)(t.a,{href:"https://github.com/scalameta/metals/pull/2781",children:"#2781"}),"\n(",(0,r.jsx)(t.a,{href:"https://github.com/tgodzik",children:"tgodzik"}),")"]}),"\n"]})]})}function d(e={}){let{wrapper:t}={...(0,l.a)(),...e.components};return t?(0,r.jsx)(t,{...e,children:(0,r.jsx)(o,{...e})}):o(e)}},50065:function(e,t,s){s.d(t,{Z:function(){return a},a:function(){return i}});var n=s(67294);let r={},l=n.createContext(r);function i(e){let t=n.useContext(l);return n.useMemo(function(){return"function"==typeof e?e(t):{...t,...e}},[t,e])}function a(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(r):e.components||r:i(e.components),n.createElement(l.Provider,{value:t},e.children)}},87164:function(e){e.exports=JSON.parse('{"permalink":"/metals/blog/2021/05/31/tungsten","source":"@site/blog/2021-05-31-tungsten.md","title":"Metals v0.10.4 - Tungsten","description":"We\'re happy to announce the release of Metals v0.10.4, which adds support for","date":"2021-05-31T00:00:00.000Z","tags":[],"readingTime":1.315,"hasTruncateMarker":false,"authors":[{"name":"Vadim Chelyshov","url":"https://twitter.com/_dos65","imageURL":"https://github.com/dos65.png","key":"dos65","page":null}],"frontMatter":{"authors":"dos65","title":"Metals v0.10.4 - Tungsten"},"unlisted":false,"prevItem":{"title":"Metals v0.10.5 - Tungsten","permalink":"/metals/blog/2021/07/14/tungsten"},"nextItem":{"title":"Metals v0.10.3 - Tungsten","permalink":"/metals/blog/2021/05/17/tungsten"}}')}}]);