"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[3172],{4617:(e,t,o)=>{o.r(t),o.d(t,{assets:()=>i,contentTitle:()=>a,default:()=>c,frontMatter:()=>r,metadata:()=>l,toc:()=>m});var n=o(4848),s=o(8453);const r={author:"\xd3lafur P\xe1ll Geirsson",title:"Low-memory symbol indexing with bloom filters",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"},a=void 0,l={permalink:"/metals/blog/2019/01/22/bloom-filters",source:"@site/blog/2019-01-22-bloom-filters.md",title:"Low-memory symbol indexing with bloom filters",description:"The latest Metals release introduces three new in-memory indexes to implement",date:"2019-01-22T00:00:00.000Z",tags:[],readingTime:12.545,hasTruncateMarker:!0,authors:[{name:"\xd3lafur P\xe1ll Geirsson",url:"https://twitter.com/olafurpg",imageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"}],frontMatter:{author:"\xd3lafur P\xe1ll Geirsson",title:"Low-memory symbol indexing with bloom filters",authorURL:"https://twitter.com/olafurpg",authorImageURL:"https://avatars2.githubusercontent.com/u/1408093?s=460&v=4"},unlisted:!1,prevItem:{title:"Metals v0.4.0 - Tin",permalink:"/metals/blog/2019/01/24/tin"},nextItem:{title:"Metals v0.3.2 - Iron",permalink:"/metals/blog/2018/12/14/iron"}},i={authorsImageUrls:[void 0]},m=[];function u(e){const t={a:"a",p:"p",...(0,s.R)(),...e.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(t.p,{children:'The latest Metals release introduces three new in-memory indexes to implement\nthe features "find symbol references" and "fuzzy symbol search". Indexes are\nimportant to provide fast response times for user requests but they come at the\nprice of higher memory usage. To keep memory usage low, Metals uses a data\nstructure called bloom filters that implements space-efficient sets. Thanks to\nbloom filters, the three new indexes added in the last release use only a few\nmegabytes of memory even for large projects with >500k lines of code.'}),"\n",(0,n.jsxs)(t.p,{children:["In this post, we look into how Metals uses bloom filters for fast indexing with\nsmall memory footprint. We explain what bloom filters are and how we can encode\nproblems like fuzzy searching to take advantage of the nice properties of bloom\nfilters. Finally, we evaluate these new features on a real-world project: the\n",(0,n.jsx)(t.a,{href:"https://github.com/akka/akka",children:"Akka"})," build."]})]})}function c(e={}){const{wrapper:t}={...(0,s.R)(),...e.components};return t?(0,n.jsx)(t,{...e,children:(0,n.jsx)(u,{...e})}):u(e)}},8453:(e,t,o)=>{o.d(t,{R:()=>a,x:()=>l});var n=o(6540);const s={},r=n.createContext(s);function a(e){const t=n.useContext(r);return n.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function l(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(s):e.components||s:a(e.components),n.createElement(r.Provider,{value:t},e.children)}}}]);