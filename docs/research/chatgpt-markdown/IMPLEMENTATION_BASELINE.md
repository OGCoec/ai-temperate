# Markdown to DOM/View implementation baseline

The frontend boundary is:

~~~
SSE snapshot/delta
  -> ai-markdown-render-state.js
  -> cumulative responseText
  -> ai-markdown-parser.js
  -> whitelist AST
  -> user-markdown-* Vue components
  -> UniApp H5 DOM / APP-PLUS View
~~~

The renderer does not infer dialogs from quotes, dashes, ellipses, or quotation marks. Dialogs and actions must arrive as separate typed UI block events and must be reduced through a server-approved block type allowlist.

The code block surface follows a dark OLED direction: high-contrast text, muted dark surfaces, a stable 44px copy target, visible copy status, horizontal scrolling for long lines, and reduced-motion-safe interaction. It intentionally does not add floating ellipsis controls above code.

Implemented files:

- fornted/common/aichat/ai-markdown-parser.js
- fornted/common/aichat/ai-markdown-render-state.js
- fornted/components/user/workspace/user-markdown-message.vue
- fornted/components/user/workspace/user-markdown-node.vue
- fornted/components/user/workspace/user-markdown-inline.vue
- fornted/components/user/workspace/user-markdown-code-block.vue
- fornted/components/user/workspace/user-markdown-table.vue
- fornted/common/aichat/ai-conversation-ui-block.js
- fornted/components/user/workspace/user-dialog-block.vue

The parser treats raw HTML as text, sanitizes link protocols, normalizes language labels, and never returns executable HTML. The component contract forbids v-html, innerHTML, dynamic component names, model-provided event handlers, and code execution.
