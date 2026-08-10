package com.augmentcode.intellij.settings;

import com.augmentcode.platform.webview_communication.settings.SettingsWebViewCommunicationTypes.IndexingState;
import com.augmentcode.platform.webview_communication.settings.SettingsWebViewCommunicationTypes.IndexingStatusResponse;
import com.augmentcode.platform.webview_communication.settings.SettingsWebViewCommunicationTypes.LanguageStat;
import com.augmentcode.platform.webview_communication.settings.SettingsWebViewCommunicationTypes.Workspace;
import com.augmentcode.platform.webview_communication.settings.SettingsWebViewCommunicationTypes.WorkspaceInfoResponse;
import com.augmentcode.platform.webview_communication.settings.SettingsWebViewCommunicationTypes.WorkspaceLanguagesResponse;
import com.augmentcode.platform.webview_communication.settings.SettingsWebViewCommunicationTypes.WorkspaceStats;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AugmentWorkspaceBridge {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .build();
    private static final List<String> SKIP_DIRS = List.of(
        ".git", "node_modules", "vendor", ".idea", "dist", "build", "target",
        ".next", ".cache", ".gradle", ".kotlin", ".intellijPlatform", ".claude",
        ".agents", ".contextengine", ".github", "coverage", "__pycache__", ".venv",
        "venv", ".tox", "out"
    );
    private static final Map<String, String> LANGUAGES = languageMap();

    private AugmentWorkspaceBridge() {}

    public static WorkspaceInfoResponse buildWorkspaceInfoResponse() {
        WorkspaceInfoResponse.Builder response = WorkspaceInfoResponse.newBuilder();
        try {
            String[] project = currentProject();
            if (project == null) {
                return response.build();
            }
            Workspace.Builder workspace = Workspace.newBuilder()
                .setId(project[0])
                .setName(project[0]);
            if (project[1] != null && !project[1].isEmpty()) {
                workspace.setPath(project[1]);
            }
            // Activate before reading stats. The tenant status endpoint is
            // workspace-scoped, so querying first can return the previous
            // project (or the empty fallback) on a fresh IDE session.
            notifyBackendActivate(project[1]);
            int[] stats = fetchWorkspaceStats();
            workspace.setStats(WorkspaceStats.newBuilder()
                .setTrackedFiles(stats[0])
                .setTotalThreads(stats[1]));
            response.addWorkspaces(workspace);
        } catch (Throwable ignored) {
            // Home remains usable when the local backend is unavailable.
        }
        return response.build();
    }

    private static int[] fetchWorkspaceStats() {
        try {
            HttpResponse<String> response = HTTP.send(
                request("/contextengine/index-status").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 200) {
                return new int[]{
                    jsonInt(response.body(), "fileCount"),
                    jsonInt(response.body(), "totalThreads")
                };
            }
        } catch (Throwable ignored) {
            // Return unknown/zero counts while the backend starts.
        }
        return new int[]{0, 0};
    }

    public static IndexingStatusResponse getIndexingStatusResponse() {
        boolean indexed = false;
        try {
            HttpResponse<String> response = HTTP.send(
                request("/contextengine/index-status").GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            indexed = response.statusCode() == 200
                && response.body() != null
                && response.body().contains("\"indexed\":true");
        } catch (Throwable ignored) {
            // Report running rather than crashing the settings bridge.
        }
        return IndexingStatusResponse.newBuilder()
            .setState(indexed ? IndexingState.INDEXING_STATE_DONE : IndexingState.INDEXING_STATE_RUNNING)
            .setIsInitialIndexing(!indexed)
            .build();
    }

    private static void notifyBackendActivate(String root) {
        if (root == null || root.isEmpty()) {
            return;
        }
        try {
            String body = "{\"host_root\":\"" + jsonEscape(root) + "\"}";
            HTTP.send(
                request("/contextengine/activate")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            );
        } catch (Throwable ignored) {
            // Activation is best effort; chat also binds the workspace.
        }
    }

    public static WorkspaceLanguagesResponse buildWorkspaceLanguagesResponse() {
        WorkspaceLanguagesResponse.Builder response = WorkspaceLanguagesResponse.newBuilder();
        try {
            String[] project = currentProject();
            if (project == null || project[1] == null) {
                return response.build();
            }
            Map<String, Integer> counts = new HashMap<>();
            int[] total = {0};
            scanDir(new File(project[1]), 0, counts, total);
            if (total[0] == 0) {
                return response.build();
            }
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
            for (int i = 0; i < Math.min(10, sorted.size()); i++) {
                Map.Entry<String, Integer> entry = sorted.get(i);
                response.addLanguages(LanguageStat.newBuilder()
                    .setName(entry.getKey())
                    .setFileCount(entry.getValue())
                    .setPercentage(100.0f * entry.getValue() / total[0]));
            }
        } catch (Throwable ignored) {
            // Language statistics are optional Home metadata.
        }
        return response.build();
    }

    private static HttpRequest.Builder request(String path) {
        String base = System.getProperty("augmentcode.tenant.url");
        if (base == null || base.isBlank()) {
            base = System.getenv("AUGMENT_TENANT_URL");
        }
        if (base == null || base.isBlank()) {
            base = "http://127.0.0.1:8787";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return HttpRequest.newBuilder()
            .uri(URI.create(base + path))
            .timeout(REQUEST_TIMEOUT);
    }

    private static int jsonInt(String json, String field) {
        if (json == null) {
            return 0;
        }
        String marker = "\"" + field + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            return 0;
        }
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return end == start ? 0 : Integer.parseInt(json.substring(start, end));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String, String> languageMap() {
        Map<String, String> languages = new HashMap<>();
        String[][] entries = {
            {".go", "Go"}, {".py", "Python"}, {".js", "JavaScript"},
            {".ts", "TypeScript"}, {".tsx", "TypeScript"}, {".jsx", "JavaScript"},
            {".java", "Java"}, {".kt", "Kotlin"}, {".rs", "Rust"},
            {".c", "C"}, {".cpp", "C++"}, {".cs", "C#"}, {".rb", "Ruby"},
            {".php", "PHP"}, {".swift", "Swift"}, {".sh", "Shell"},
            {".sql", "SQL"}, {".html", "HTML"}, {".css", "CSS"},
            {".md", "Markdown"}, {".json", "JSON"}, {".yaml", "YAML"},
            {".yml", "YAML"}, {".toml", "TOML"}, {".xml", "XML"},
            {".proto", "Protobuf"}, {".lua", "Lua"}, {".dart", "Dart"},
            {".ex", "Elixir"}
        };
        for (String[] entry : entries) {
            languages.put(entry[0], entry[1]);
        }
        return languages;
    }

    private static void scanDir(File directory, int depth, Map<String, Integer> counts, int[] total) {
        if (depth > 3) {
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (!SKIP_DIRS.contains(file.getName())) {
                    scanDir(file, depth + 1, counts, total);
                }
                continue;
            }
            total[0]++;
            String language = LANGUAGES.getOrDefault(extension(file.getName()).toLowerCase(), "Other");
            counts.merge(language, 1, Integer::sum);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    private static String[] currentProject() throws Exception {
        Object project = focusedProject();
        if (project == null) {
            Class<?> projectManagerClass = Class.forName("com.intellij.openapi.project.ProjectManager");
            Object projectManager = projectManagerClass.getMethod("getInstance").invoke(null);
            Object[] openProjects = (Object[]) projectManagerClass.getMethod("getOpenProjects").invoke(projectManager);
            if (openProjects.length > 0) {
                project = openProjects[0];
            }
        }
        if (project == null) {
            return null;
        }
        String name = (String) project.getClass().getMethod("getName").invoke(project);
        String path = (String) project.getClass().getMethod("getBasePath").invoke(project);
        return new String[]{name == null ? "default" : name, path};
    }

    private static Object focusedProject() {
        try {
            Class<?> windowManagerClass = Class.forName("com.intellij.openapi.wm.WindowManager");
            Object windowManager = windowManagerClass.getMethod("getInstance").invoke(null);
            Class<?> projectClass = Class.forName("com.intellij.openapi.project.Project");
            Object frame = windowManagerClass.getMethod("getIdeFrame", projectClass)
                .invoke(windowManager, new Object[]{null});
            return frame == null ? null : frame.getClass().getMethod("getProject").invoke(frame);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
