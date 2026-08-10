package tenant

// generateProjectOverview scans a workspace root and produces a concise
// plain-text project overview (tech stack, structure, languages).  It backs
// the IDE webview's Home → Codebase summary pipeline.

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// overviewRequest is the body the sidecar forwards from the webview.
type overviewRequest struct {
	WorkspaceID  string `json:"workspace_id"`
	WorkspaceRoot string `json:"workspace_root"`
}

// langExt maps file extensions to a display language name.
var langExt = map[string]string{
	".go": "Go", ".py": "Python", ".js": "JavaScript", ".ts": "TypeScript",
	".tsx": "TypeScript", ".jsx": "JavaScript", ".java": "Java", ".kt": "Kotlin",
	".rs": "Rust", ".c": "C", ".h": "C", ".cpp": "C++", ".hpp": "C++", ".cs": "C#",
	".rb": "Ruby", ".php": "PHP", ".swift": "Swift", ".scala": "Scala", ".sh": "Shell",
	".zsh": "Shell", ".bash": "Shell", ".sql": "SQL", ".html": "HTML", ".css": "CSS",
	".vue": "Vue", ".svelte": "Svelte", ".md": "Markdown", ".json": "JSON",
	".yaml": "YAML", ".yml": "YAML", ".toml": "TOML", ".xml": "XML",
	".proto": "Protobuf", ".dockerfile": "Dockerfile", ".lua": "Lua", ".r": "R",
	".dart": "Dart", ".ex": "Elixir", ".exs": "Elixir", ".hs": "Haskell",
}

// manifestHints maps a project manifest filename to the tech stack it signals.
var manifestHints = []struct{ file, stack string }{
	{"go.mod", "Go (Go modules)"},
	{"Cargo.toml", "Rust (Cargo)"},
	{"package.json", "Node.js / JavaScript"},
	{"tsconfig.json", "TypeScript"},
	{"pyproject.toml", "Python (pyproject)"},
	{"requirements.txt", "Python (pip)"},
	{"setup.py", "Python (setuptools)"},
	{"pom.xml", "Java (Maven)"},
	{"build.gradle", "Java/Kotlin (Gradle)"},
	{"build.gradle.kts", "Kotlin (Gradle)"},
	{"Gemfile", "Ruby (Bundler)"},
	{"composer.json", "PHP (Composer)"},
	{"mix.exs", "Elixir (Mix)"},
	{"Makefile", "Make"},
	{"CMakeLists.txt", "CMake"},
	{"Dockerfile", "Docker"},
	{"docker-compose.yml", "Docker Compose"},
	{"docker-compose.yaml", "Docker Compose"},
	{"package-lock.json", "Node.js"},
	{"pnpm-lock.yaml", "Node.js (pnpm)"},
	{"yarn.lock", "Node.js (Yarn)"},
}

// skipDirs are directory names not counted during the scan.
var skipDirs = map[string]bool{
	".git": true, "node_modules": true, "vendor": true, ".idea": true,
	"dist": true, "build": true, "target": true, ".next": true, ".cache": true,
	".claude": true, ".agents": true, "coverage": true, "__pycache__": true,
	".venv": true, "venv": true, ".tox": true, "out": true,
	// IDE / build tooling caches that would dominate the count.
	".intellijPlatform": true, ".kotlin": true, ".gradle": true,
	".gradle-lock-patch": true, ".vite": true, ".contextengine": true,
	".github": true, ".gradleLock": true, "gradle": true, ".idea-artifacts": true,
	".renders": true, ".outputs": true,
}

const overviewScanDepth = 3

// handleGenerateProjectOverview serves POST /generate-project-overview.
func (s *Server) handleGenerateProjectOverview(w http.ResponseWriter, r *http.Request) {
	var req overviewRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeJSON(w, 400, map[string]any{"error": "invalid request: " + err.Error()})
		return
	}
	root := req.WorkspaceRoot
	if root == "" {
		// Fall back to a reasonable default when the sidecar didn't pass a root.
		root = "/workspace"
	}
	text := buildOverview(root)
	s.writeJSON(w, 200, map[string]any{"text": text})
}

// buildOverview scans root and renders a summary.
func buildOverview(root string) string {
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		return fmt.Sprintf("Codebase overview for %s:\n- Workspace path: %s\n- (workspace not readable)\n", root, root)
	}

	langCount := map[string]int{}
	totalFiles := 0
	dirs := []string{}
	stacks := []string{}
	topLevel := []string{}

	// Top-level listing + manifest detection.
	entries, _ := os.ReadDir(root)
	for _, e := range entries {
		name := e.Name()
		if skipDirs[name] {
			continue
		}
		if e.IsDir() {
			dirs = append(dirs, name)
		} else {
			topLevel = append(topLevel, name)
			for _, m := range manifestHints {
				if name == m.file {
					stacks = appendUnique(stacks, m.stack)
				}
			}
		}
	}

	// Recursive language scan (bounded depth).
	scanDir(root, 0, langCount, &totalFiles)

	// Sort languages by file count, descending.
	type lc struct {
		lang string
		n    int
	}
	langs := make([]lc, 0, len(langCount))
	for l, n := range langCount {
		langs = append(langs, lc{l, n})
	}
	sort.Slice(langs, func(i, j int) bool { return langs[i].n > langs[j].n })

	var b strings.Builder
	b.WriteString(fmt.Sprintf("Codebase overview for %s\n", filepath.Base(root)))
	b.WriteString(fmt.Sprintf("\n- Path: %s\n", root))
	b.WriteString(fmt.Sprintf("- Files scanned: %d\n", totalFiles))
	if len(dirs) > 0 {
		b.WriteString(fmt.Sprintf("- Directories: %s\n", strings.Join(dirs, ", ")))
	}
	if len(stacks) > 0 {
		b.WriteString(fmt.Sprintf("- Tech stack: %s\n", strings.Join(stacks, ", ")))
	}
	if len(topLevel) > 0 {
		sorted := append([]string(nil), topLevel...)
		sort.Strings(sorted)
		shown := sorted
		if len(shown) > 12 {
			shown = shown[:12]
		}
		b.WriteString(fmt.Sprintf("- Key files: %s\n", strings.Join(shown, ", ")))
	}
	if len(langs) > 0 {
		b.WriteString("- Languages:\n")
		shown := langs
		if len(shown) > 8 {
			shown = langs[:8]
		}
		for _, l := range shown {
			b.WriteString(fmt.Sprintf("  - %s (%d files)\n", l.lang, l.n))
		}
	}
	return strings.TrimRight(b.String(), "\n")
}

// scanDir walks root up to depth levels, counting files by language.
func scanDir(dir string, depth int, langCount map[string]int, total *int) {
	if depth > overviewScanDepth {
		return
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		name := e.Name()
		if skipDirs[name] {
			continue
		}
		if e.IsDir() {
			scanDir(filepath.Join(dir, name), depth+1, langCount, total)
			continue
		}
		*total++
		ext := strings.ToLower(filepath.Ext(name))
		if lang, ok := langExt[ext]; ok {
			langCount[lang]++
		} else {
			langCount["Other"]++
		}
	}
}

func appendUnique(list []string, v string) []string {
	for _, x := range list {
		if x == v {
			return list
		}
	}
	return append(list, v)
}
