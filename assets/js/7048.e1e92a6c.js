"use strict";(self.webpackChunk=self.webpackChunk||[]).push([[7048],{6535:(e,t,a)=>{a.d(t,{A:()=>A});var r=a(1367),n=a(6540),i=a(4164),s=a(9909),l=a(4581),o=a(8774),c=a(1312),d=a(6347),u=a(9169);function m(e){var t=(0,d.zy)().pathname;return(0,n.useMemo)((function(){return e.filter((function(e){return function(e,t){return!(e.unlisted&&!(0,u.ys)(e.permalink,t))}(e,t)}))}),[e,t])}const g={sidebar:"sidebar_re4s",sidebarItemTitle:"sidebarItemTitle_pO2u",sidebarItemList:"sidebarItemList_Yudw",sidebarItem:"sidebarItem__DBe",sidebarItemLink:"sidebarItemLink_mo7H",sidebarItemLinkActive:"sidebarItemLinkActive_I1ZP"};var h=a(4848);function f(e){var t=e.sidebar,a=m(t.items);return(0,h.jsx)("aside",{className:"col col--3",children:(0,h.jsxs)("nav",{className:(0,i.A)(g.sidebar,"thin-scrollbar"),"aria-label":(0,c.T)({id:"theme.blog.sidebar.navAriaLabel",message:"Blog recent posts navigation",description:"The ARIA label for recent posts in the blog sidebar"}),children:[(0,h.jsx)("div",{className:(0,i.A)(g.sidebarItemTitle,"margin-bottom--md"),children:t.title}),(0,h.jsx)("ul",{className:(0,i.A)(g.sidebarItemList,"clean-list"),children:a.map((function(e){return(0,h.jsx)("li",{className:g.sidebarItem,children:(0,h.jsx)(o.A,{isNavLink:!0,to:e.permalink,className:g.sidebarItemLink,activeClassName:g.sidebarItemLinkActive,children:e.title})},e.permalink)}))})]})})}var v=a(5600);function p(e){var t=m(e.sidebar.items);return(0,h.jsx)("ul",{className:"menu__list",children:t.map((function(e){return(0,h.jsx)("li",{className:"menu__list-item",children:(0,h.jsx)(o.A,{isNavLink:!0,to:e.permalink,className:"menu__link",activeClassName:"menu__link--active",children:e.title})},e.permalink)}))})}function b(e){return(0,h.jsx)(v.GX,{component:p,props:e})}function x(e){var t=e.sidebar,a=(0,l.l)();return null!=t&&t.items.length?"mobile"===a?(0,h.jsx)(b,{sidebar:t}):(0,h.jsx)(f,{sidebar:t}):null}var j=["sidebar","toc","children"];function A(e){var t=e.sidebar,a=e.toc,n=e.children,l=(0,r.A)(e,j),o=t&&t.items.length>0;return(0,h.jsx)(s.A,Object.assign({},l,{children:(0,h.jsx)("div",{className:"container margin-vert--lg",children:(0,h.jsxs)("div",{className:"row",children:[(0,h.jsx)(x,{sidebar:t}),(0,h.jsx)("main",{className:(0,i.A)("col",{"col--7":o,"col--9 col--offset-1":!o}),children:n}),a&&(0,h.jsx)("div",{className:"col col--2",children:a})]})})}))}},4651:(e,t,a)=>{a.d(t,{A:()=>L});a(6540);var r=a(4164),n=a(7131),i=a(4848);function s(e){var t=e.children,a=e.className;return(0,i.jsx)("article",{className:a,children:t})}var l=a(8774);const o={title:"title_f1Hy"};function c(e){var t=e.className,a=(0,n.e)(),s=a.metadata,c=a.isBlogPostPage,d=s.permalink,u=s.title,m=c?"h1":"h2";return(0,i.jsx)(m,{className:(0,r.A)(o.title,t),children:c?u:(0,i.jsx)(l.A,{to:d,children:u})})}var d=a(1312),u=a(5846),m=a(6266);const g={container:"container_mt6G"};function h(e){var t,a=e.readingTime,r=(t=(0,u.W)().selectMessage,function(e){var a=Math.ceil(e);return t(a,(0,d.T)({id:"theme.blog.post.readingTime.plurals",description:'Pluralized label for "{readingTime} min read". Use as much plural forms (separated by "|") as your language support (see https://www.unicode.org/cldr/cldr-aux/charts/34/supplemental/language_plural_rules.html)',message:"One min read|{readingTime} min read"},{readingTime:a}))});return(0,i.jsx)(i.Fragment,{children:r(a)})}function f(e){var t=e.date,a=e.formattedDate;return(0,i.jsx)("time",{dateTime:t,children:a})}function v(){return(0,i.jsx)(i.Fragment,{children:" \xb7 "})}function p(e){var t,a=e.className,s=(0,n.e)().metadata,l=s.date,o=s.readingTime,c=(0,m.i)({day:"numeric",month:"long",year:"numeric",timeZone:"UTC"});return(0,i.jsxs)("div",{className:(0,r.A)(g.container,"margin-vert--md",a),children:[(0,i.jsx)(f,{date:l,formattedDate:(t=l,c.format(new Date(t)))}),void 0!==o&&(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(v,{}),(0,i.jsx)(h,{readingTime:o})]})]})}function b(e){return e.href?(0,i.jsx)(l.A,Object.assign({},e)):(0,i.jsx)(i.Fragment,{children:e.children})}function x(e){var t=e.author,a=e.className,n=t.name,s=t.title,l=t.url,o=t.imageURL,c=t.email,d=l||c&&"mailto:"+c||void 0;return(0,i.jsxs)("div",{className:(0,r.A)("avatar margin-bottom--sm",a),children:[o&&(0,i.jsx)(b,{href:d,className:"avatar__photo-link",children:(0,i.jsx)("img",{className:"avatar__photo",src:o,alt:n})}),n&&(0,i.jsxs)("div",{className:"avatar__intro",children:[(0,i.jsx)("div",{className:"avatar__name",children:(0,i.jsx)(b,{href:d,children:(0,i.jsx)("span",{children:n})})}),s&&(0,i.jsx)("small",{className:"avatar__subtitle",children:s})]})]})}const j={authorCol:"authorCol_Hf19",imageOnlyAuthorRow:"imageOnlyAuthorRow_pa_O",imageOnlyAuthorCol:"imageOnlyAuthorCol_G86a"};function A(e){var t=e.className,a=(0,n.e)(),s=a.metadata.authors,l=a.assets;if(0===s.length)return null;var o=s.every((function(e){return!e.name}));return(0,i.jsx)("div",{className:(0,r.A)("margin-top--md margin-bottom--sm",o?j.imageOnlyAuthorRow:"row",t),children:s.map((function(e,t){var a;return(0,i.jsx)("div",{className:(0,r.A)(!o&&"col col--6",o?j.imageOnlyAuthorCol:j.authorCol),children:(0,i.jsx)(x,{author:Object.assign({},e,{imageURL:null!=(a=l.authorsImageUrls[t])?a:e.imageURL})})},t)}))})}function N(){return(0,i.jsxs)("header",{children:[(0,i.jsx)(c,{}),(0,i.jsx)(p,{}),(0,i.jsx)(A,{})]})}var P=a(440),k=a(759);function _(e){var t=e.children,a=e.className,s=(0,n.e)().isBlogPostPage;return(0,i.jsx)("div",{id:s?P.blogPostContainerID:void 0,className:(0,r.A)("markdown",a),children:(0,i.jsx)(k.A,{children:t})})}var w=a(7559),y=a(4336),I=a(8046),O=a(1367),T=["blogPostTitle"];function B(){return(0,i.jsx)("b",{children:(0,i.jsx)(d.A,{id:"theme.blog.post.readMore",description:"The label used in blog post item excerpts to link to full blog posts",children:"Read More"})})}function M(e){var t=e.blogPostTitle,a=(0,O.A)(e,T);return(0,i.jsx)(l.A,Object.assign({"aria-label":(0,d.T)({message:"Read more about {title}",id:"theme.blog.post.readMoreLabel",description:"The ARIA label for the link to full blog posts from excerpts"},{title:t})},a,{children:(0,i.jsx)(B,{})}))}function U(){var e=(0,n.e)(),t=e.metadata,a=e.isBlogPostPage,s=t.tags,l=t.title,o=t.editUrl,c=t.hasTruncateMarker,d=t.lastUpdatedBy,u=t.lastUpdatedAt,m=!a&&c,g=s.length>0;if(!(g||m||o))return null;if(a){var h=!!(o||u||d);return(0,i.jsxs)("footer",{className:"docusaurus-mt-lg",children:[g&&(0,i.jsx)("div",{className:(0,r.A)("row","margin-top--sm",w.G.blog.blogFooterEditMetaRow),children:(0,i.jsx)("div",{className:"col",children:(0,i.jsx)(I.A,{tags:s})})}),h&&(0,i.jsx)(y.A,{className:(0,r.A)("margin-top--sm",w.G.blog.blogFooterEditMetaRow),editUrl:o,lastUpdatedAt:u,lastUpdatedBy:d})]})}return(0,i.jsxs)("footer",{className:"row docusaurus-mt-lg",children:[g&&(0,i.jsx)("div",{className:(0,r.A)("col",{"col--9":m}),children:(0,i.jsx)(I.A,{tags:s})}),m&&(0,i.jsx)("div",{className:(0,r.A)("col text--right",{"col--3":g}),children:(0,i.jsx)(M,{blogPostTitle:l,to:t.permalink})})]})}function L(e){var t=e.children,a=e.className,l=(0,n.e)().isBlogPostPage?void 0:"margin-bottom--xl";return(0,i.jsxs)(s,{className:(0,r.A)(l,a),children:[(0,i.jsx)(N,{}),(0,i.jsx)(_,{children:t}),(0,i.jsx)(U,{})]})}},7131:(e,t,a)=>{a.d(t,{e:()=>o,i:()=>l});var r=a(6540),n=a(2021),i=a(4848),s=r.createContext(null);function l(e){var t=e.children,a=e.content,n=e.isBlogPostPage,l=function(e){var t=e.content,a=e.isBlogPostPage;return(0,r.useMemo)((function(){return{metadata:t.metadata,frontMatter:t.frontMatter,assets:t.assets,toc:t.toc,isBlogPostPage:a}}),[t,a])}({content:a,isBlogPostPage:void 0!==n&&n});return(0,i.jsx)(s.Provider,{value:l,children:t})}function o(){var e=(0,r.useContext)(s);if(null===e)throw new n.dV("BlogPostProvider");return e}},6676:(e,t,a)=>{a.d(t,{k:()=>d,J:()=>u});var r=a(6025),n=a(4586),i=a(6803);var s=a(7131),l=function(e){return new Date(e).toISOString()};function o(e){var t=e.map(m);return{author:1===t.length?t[0]:t}}function c(e,t,a){return e?{image:(r={imageUrl:t(e,{absolute:!0}),caption:"title image for the blog post: "+a},n=r.imageUrl,i=r.caption,{"@type":"ImageObject","@id":n,url:n,contentUrl:n,caption:i})}:{};var r,n,i}function d(e){var t=(0,n.A)().siteConfig,a=(0,r.hH)().withBaseUrl,i=e.metadata,s=i.blogDescription,d=i.blogTitle,u=i.permalink,m=""+t.url+u;return{"@context":"https://schema.org","@type":"Blog","@id":m,mainEntityOfPage:m,headline:d,description:s,blogPost:e.items.map((function(e){return function(e,t,a){var r,n,i=e.assets,s=e.frontMatter,d=e.metadata,u=d.date,m=d.title,g=d.description,h=d.lastUpdatedAt,f=null!=(r=i.image)?r:s.image,v=null!=(n=s.keywords)?n:[],p=""+t.url+d.permalink,b=h?l(h):void 0;return Object.assign({"@type":"BlogPosting","@id":p,mainEntityOfPage:p,url:p,headline:m,name:m,description:g,datePublished:u},b?{dateModified:b}:{},o(d.authors),c(f,a,m),v?{keywords:v}:{})}(e.content,t,a)}))}}function u(){var e,t,a=function(){var e,t=(0,i.A)(),a=null==t||null==(e=t.data)?void 0:e.blogMetadata;if(!a)throw new Error("useBlogMetadata() can't be called on the current route because the blog metadata could not be found in route context");return a}(),d=(0,s.e)(),u=d.assets,m=d.metadata,g=(0,n.A)().siteConfig,h=(0,r.hH)().withBaseUrl,f=m.date,v=m.title,p=m.description,b=m.frontMatter,x=m.lastUpdatedAt,j=null!=(e=u.image)?e:b.image,A=null!=(t=b.keywords)?t:[],N=x?l(x):void 0,P=""+g.url+m.permalink;return Object.assign({"@context":"https://schema.org","@type":"BlogPosting","@id":P,mainEntityOfPage:P,url:P,headline:v,name:v,description:p,datePublished:f},N?{dateModified:N}:{},o(m.authors),c(j,h,v),A?{keywords:A}:{},{isPartOf:{"@type":"Blog","@id":""+g.url+a.blogBasePath,name:a.blogTitle}})}function m(e){return Object.assign({"@type":"Person"},e.name?{name:e.name}:{},e.title?{description:e.title}:{},e.url?{url:e.url}:{},e.email?{email:e.email}:{},e.imageURL?{image:e.imageURL}:{})}},5846:(e,t,a)=>{a.d(t,{W:()=>c});var r=a(6540),n=a(4586),i=["zero","one","two","few","many","other"];function s(e){return i.filter((function(t){return e.includes(t)}))}var l={locale:"en",pluralForms:s(["one","other"]),select:function(e){return 1===e?"one":"other"}};function o(){var e=(0,n.A)().i18n.currentLocale;return(0,r.useMemo)((function(){try{return t=e,a=new Intl.PluralRules(t),{locale:t,pluralForms:s(a.resolvedOptions().pluralCategories),select:function(e){return a.select(e)}}}catch(r){return console.error('Failed to use Intl.PluralRules for locale "'+e+'".\nDocusaurus will fallback to the default (English) implementation.\nError: '+r.message+"\n"),l}var t,a}),[e])}function c(){var e=o();return{selectMessage:function(t,a){return function(e,t,a){var r=e.split("|");if(1===r.length)return r[0];r.length>a.pluralForms.length&&console.error("For locale="+a.locale+", a maximum of "+a.pluralForms.length+" plural forms are expected ("+a.pluralForms.join(",")+"), but the message contains "+r.length+": "+e);var n=a.select(t),i=a.pluralForms.indexOf(n);return r[Math.min(i,r.length-1)]}(a,t,e)}}}}}]);