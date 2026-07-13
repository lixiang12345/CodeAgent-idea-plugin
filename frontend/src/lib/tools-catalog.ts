// Generated from prototypes/assets/tools.json — final prototype tool catalog.
export type ToolCatalogEntry = {
  id: string;
  name: string;
  icon: string;
  desc: string;
  secondary?: string;
  pluginTool?: string | null;
  connected: boolean;
};

export const TOOL_CATALOG: ToolCatalogEntry[] = [
  { id: "context-engine", name: "CodeAgent Context Engine", icon: "augment-logo", desc: "\u4e0a\u4e0b\u6587\u5f15\u64ce\uff08\u552f\u4e00\u663e\u793a\u540d\uff09\u3002codebase-retrieval \u5de5\u5177\u4e5f\u6e32\u67d3\u4e3a\u6b64\u7ec4\u4ef6\uff1b\u526f\u6807\u9898\u4e3a Retrieving from: Codebase / Conversations", secondary: "Retrieving from: <> Codebase", pluginTool: "codebase_retrieval", connected: true },
  { id: "conversation-retrieval", name: "CodeAgent Context Engine", icon: "augment-logo", desc: "\u540c\u4e00\u7ec4\u4ef6\uff0c\u526f\u6807\u9898 Retrieving from: Conversations", secondary: "Retrieving from: Conversations", pluginTool: "conversation_retrieval", connected: true },
  { id: "str-replace", name: "str-replace-editor", icon: "file-pen", desc: "\u8bfb/\u5199/\u7f16\u8f91\u6587\u4ef6\uff08\u52a8\u6001\u6807\u9898\uff1aRead file / Creating / Editing\uff09", secondary: undefined, pluginTool: "replace_text", connected: true },
  { id: "view", name: "View", icon: "folder", desc: "\u67e5\u770b\u6587\u4ef6\u6216\u76ee\u5f55", secondary: undefined, pluginTool: "list_files", connected: true },
  { id: "read-file", name: "Read", icon: "file", desc: "\u8bfb\u53d6\u6587\u4ef6", secondary: undefined, pluginTool: "read_file", connected: true },
  { id: "save-file", name: "Save File", icon: "file-plus", desc: "\u4fdd\u5b58\u65b0\u6587\u4ef6", secondary: undefined, pluginTool: "write_file", connected: true },
  { id: "remove-files", name: "Remove", icon: "file-minus", desc: "\u5220\u9664\u6587\u4ef6", secondary: undefined, pluginTool: "remove_files", connected: true },
  { id: "apply-patch", name: "Apply Patch", icon: "file-diff", desc: "\u5e94\u7528\u591a\u6587\u4ef6\u8865\u4e01", secondary: undefined, pluginTool: "apply_patch", connected: true },
  { id: "grep", name: "Grep Search", icon: "search", desc: "\u4ee3\u7801\u6b63\u5219\u641c\u7d22", secondary: undefined, pluginTool: "search_text", connected: true },
  { id: "shell", name: "Terminal", icon: "square-terminal", desc: "Shell / launch-process \u7b49", secondary: undefined, pluginTool: "run_terminal", connected: true },
  { id: "web-fetch", name: "Web Fetch", icon: "globe", desc: "\u6293\u53d6 URL", secondary: undefined, pluginTool: "web_fetch", connected: true },
  { id: "web", name: "Web", icon: "globe", desc: "\u7f51\u9875\u641c\u7d22", secondary: undefined, pluginTool: null, connected: false },
  { id: "open-browser", name: "Open in Browser", icon: "external-link", desc: "\u6d4f\u89c8\u5668\u6253\u5f00", secondary: undefined, pluginTool: "open_browser", connected: true },
  { id: "diagnostics", name: "Diagnostics", icon: "circle-alert", desc: "IDE \u8bca\u65ad", secondary: undefined, pluginTool: "diagnostics", connected: true },
  { id: "git-commit", name: "Git Commit Retrieval", icon: "git-commit-horizontal", desc: "Git \u63d0\u4ea4\u68c0\u7d22", secondary: undefined, pluginTool: "git_history", connected: true },
  { id: "mermaid", name: "Render Mermaid", icon: "workflow", desc: "\u6e32\u67d3\u56fe\u8868", secondary: undefined, pluginTool: "render_mermaid", connected: true },
  { id: "add-tasks", name: "Add Tasks", icon: "list-checks", desc: "\u6dfb\u52a0\u4efb\u52a1", secondary: undefined, pluginTool: "add_tasks", connected: true },
  { id: "view-tasks", name: "View Task List", icon: "list-checks", desc: "\u67e5\u770b\u4efb\u52a1\u5217\u8868", secondary: undefined, pluginTool: "view_tasks", connected: true },
  { id: "update-tasks", name: "Update Task List", icon: "list-checks", desc: "\u66f4\u65b0\u4efb\u52a1", secondary: undefined, pluginTool: "update_tasks", connected: true },
  { id: "reorg-tasks", name: "Reorganize Task List", icon: "list-checks", desc: "\u91cd\u6392\u4efb\u52a1", secondary: undefined, pluginTool: "reorg_tasks", connected: true },
  { id: "subagent", name: "SubAgent", icon: "bot", desc: "\u5b50\u4ee3\u7406", secondary: undefined, pluginTool: null, connected: false },
  { id: "async-subagent", name: "Async SubAgent", icon: "bot", desc: "\u5f02\u6b65\u5b50\u4ee3\u7406", secondary: undefined, pluginTool: null, connected: false },
  { id: "ask-user", name: "Ask User", icon: "message-circle", desc: "\u5411\u7528\u6237\u63d0\u95ee", secondary: undefined, pluginTool: "ask_user", connected: true },
  { id: "github", name: "GitHub", icon: "github", desc: "GitHub \u96c6\u6210", secondary: undefined, pluginTool: null, connected: false },
  { id: "linear", name: "Linear", icon: "linear", desc: "Linear", secondary: undefined, pluginTool: null, connected: false },
  { id: "notion", name: "Notion", icon: "notion", desc: "Notion", secondary: undefined, pluginTool: null, connected: false },
  { id: "jira", name: "Jira", icon: "jira", desc: "Jira", secondary: undefined, pluginTool: null, connected: false },
  { id: "confluence", name: "Confluence", icon: "confluence", desc: "Confluence", secondary: undefined, pluginTool: null, connected: false },
  { id: "glean", name: "Glean", icon: "glean", desc: "Glean", secondary: undefined, pluginTool: null, connected: false },
  { id: "supabase", name: "Supabase", icon: "supabase", desc: "Supabase", secondary: undefined, pluginTool: null, connected: false },
  { id: "mcp", name: "MCP Tool", icon: "mcp", desc: "\u4efb\u610f MCP \u670d\u52a1\u5668\u5de5\u5177", secondary: undefined, pluginTool: null, connected: false },
];

export const SLASH_COMMANDS = [
  { command: "/explain", description: "Explain the selected or attached code" },
  { command: "/test", description: "Add or run focused tests" },
  { command: "/fix", description: "Find and fix a bug with a regression test" },
  { command: "/review", description: "Review the current change set" },
  { command: "/commit", description: "Open Git Changes and draft a commit" },
  { command: "/tasks", description: "Open the Active Tasklist" },
  { command: "/rules", description: "Open Rules & Guidelines" },
];

export const MENTION_KINDS = [
  { id: "file", label: "Project file", icon: "file" },
  { id: "rule", label: "Rule / guideline", icon: "book-open" },
  { id: "symbol", label: "Symbol search", icon: "code" },
] as const;
