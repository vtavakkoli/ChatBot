package com.example.chatbot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HuggingFaceModelRepositoryClient {
    // User target: allow LiteRT-LM model bundles up to 2 GB.
    private static final long MAX_MODEL_BYTES = 2L * ModelInfo.ONE_GB;

    // Keep remote scan small. The old version inspected 125 repos and also probed guessed files.
    // That made the UI wait too long. This version shows curated results first and remote-checks quickly.
    private static final int MAX_REPOS_TO_INSPECT = 45;
    private static final int MAX_MODELS_TO_SHOW = 40;
    private static final int MAX_FILES_PER_REPO = 3;
    private static final long REMOTE_SCAN_TIME_BUDGET_MS = 14000L;

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 9000;
    private static final int HEAD_CONNECT_TIMEOUT_MS = 3500;
    private static final int HEAD_READ_TIMEOUT_MS = 4500;

    public interface ModelFetchListener {
        void onStatus(String status);
        void onInitialModels(List<ModelInfo> models);
        void onModelFound(ModelInfo model);
        void onFinished(List<ModelInfo> models);
    }

    private static final String[] PRIORITY_REPOS = new String[]{
            // Best text/RAG choices under 2 GB when using LiteRT quantized files.
            "litert-community/Qwen3-0.6B",
            "litert-community/Qwen2.5-0.5B-Instruct",
            "litert-community/Gemma3-1B-IT",
            "litert-community/Qwen2.5-1.5B-Instruct",
            "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            "litert-community/TinyLlama-1.1B-Chat-v1.0",
            "litert-community/SmolLM2-360M-Instruct",
            "litert-community/SmolLM2-135M-Instruct",
            "litert-community/SmolLM-135M-Instruct",
            "litert-community/gemma-3-270m-it",
            "litert-community/Gemma2-2B-IT",

            // Optional helper models.
            "litert-community/embeddinggemma-300m",
            "litert-community/FastVLM-0.5B",
            "litert-community/SmolVLM-256M-Instruct",
            "litert-community/functiongemma-270m-ft-mobile-actions",
            "litert-community/FunctionGemma_270M_Mobile_Actions"
    };

    private static final String[] COMMON_LITERTLM_NAMES = new String[]{
            "model.litertlm",
            "qwen3_0_6b_mixed_int4.litertlm",
            "Qwen3-0.6B.litertlm",
            "gemma3-270m-it-q8.litertlm",
            "mobile_actions_q8_ekv1024.litertlm",
            "functiongemma-mobile-actions_q8_ekv1024.litertlm"
    };

    /**
     * Backward-compatible method used by your existing UI.
     * It now returns a curated list immediately plus a short remote enrichment scan.
     */
    public List<ModelInfo> fetchSmallLiteRtLmModels() throws Exception {
        return fetchSmallLiteRtLmModels(null);
    }

    /**
     * Better method for Android UI:
     * - listener.onInitialModels(...) can be displayed immediately.
     * - remote scan enriches the list for a few seconds.
     * - no long wait for every Hugging Face result.
     */
    public List<ModelInfo> fetchSmallLiteRtLmModels(ModelFetchListener listener) throws Exception {
        long deadline = System.currentTimeMillis() + REMOTE_SCAN_TIME_BUDGET_MS;
        LinkedHashMap<String, ModelInfo> models = new LinkedHashMap<>();

        addFallbackMissing(models);
        List<ModelInfo> initial = sortedForDisplay(new ArrayList<>(models.values()));
        if (listener != null) {
            listener.onInitialModels(initial);
            listener.onStatus("Showing recommended models first. Checking Hugging Face in background...");
        }

        // Step 1: inspect only priority repos first.
        inspectRepos(priorityRepoSummaries(), models, deadline, listener);

        // Step 2: inspect top LiteRT community repos only if we still have time and not enough results.
        if (System.currentTimeMillis() < deadline && models.size() < 24) {
            try {
                if (listener != null) listener.onStatus("Checking top LiteRT community repositories...");
                inspectRepos(fetchLiteRtCommunityRepos(), models, deadline, listener);
            } catch (Exception error) {
                if (listener != null) listener.onStatus("Repository scan skipped: " + error.getClass().getSimpleName());
            }
        }

        List<ModelInfo> finalList = sortedForDisplay(new ArrayList<>(models.values()));
        if (finalList.size() > MAX_MODELS_TO_SHOW) {
            finalList = new ArrayList<>(finalList.subList(0, MAX_MODELS_TO_SHOW));
        }
        if (listener != null) listener.onFinished(finalList);
        return finalList;
    }

    /**
     * Use this to populate the UI instantly before starting network calls.
     */
    public List<ModelInfo> quickRecommendedModels() {
        LinkedHashMap<String, ModelInfo> models = new LinkedHashMap<>();
        addFallbackMissing(models);
        return sortedForDisplay(new ArrayList<>(models.values()));
    }

    /**
     * Nice compact text for a TextView. Do not print long raw URLs in the list.
     */
    public static String formatModelListForDisplay(List<ModelInfo> models) {
        if (models == null || models.isEmpty()) {
            return "No LiteRT-LM models found. Check internet connection or open the model repository.";
        }

        StringBuilder text = new StringBuilder();
        text.append("LiteRT models up to 2 GB\n");
        text.append("Recommended order: chat/RAG first, then vision/embedding helpers.\n\n");

        int index = 1;
        for (ModelInfo model : sortedForDisplay(new ArrayList<>(models))) {
            text.append(index).append(". ").append(iconFor(model)).append(" ")
                    .append(shortName(model.repoId)).append("\n");
            text.append("   Repo: ").append(model.repoId).append("\n");
            text.append("   File: ").append(shortenMiddle(model.fileName, 58)).append("\n");
            text.append("   Size: ").append(formatSize(model.sizeBytes)).append("\n");
            text.append("   Use: ").append(useCaseFor(model)).append("\n");
            if (model.note != null && !model.note.trim().isEmpty()) {
                text.append("   Note: ").append(model.note.trim()).append("\n");
            }
            text.append("\n");
            index++;
        }
        return text.toString().trim();
    }

    private void inspectRepos(List<RepoSummary> repos,
                              LinkedHashMap<String, ModelInfo> models,
                              long deadline,
                              ModelFetchListener listener) {
        for (RepoSummary repo : repos) {
            if (System.currentTimeMillis() >= deadline) break;
            if (models.size() >= MAX_MODELS_TO_SHOW) break;
            if (!isUsefulRepo(repo.repoId)) continue;

            try {
                List<ModelInfo> repoModels = fetchSmallFilesForRepo(repo.repoId, repo.downloads, deadline);
                for (ModelInfo info : repoModels) {
                    if (System.currentTimeMillis() >= deadline) break;
                    String key = info.repoId + "/" + info.fileName;
                    ModelInfo previous = models.put(key, info);
                    if (listener != null && previous == null) listener.onModelFound(info);
                }
            } catch (Exception ignored) {
                // Continue. Many HF repos are gated, moved, or temporarily unavailable.
            }
        }
    }

    private List<RepoSummary> priorityRepoSummaries() {
        List<RepoSummary> repos = new ArrayList<>();
        for (String repoId : PRIORITY_REPOS) {
            repos.add(new RepoSummary(repoId, 0));
        }
        return repos;
    }

    private List<RepoSummary> fetchLiteRtCommunityRepos() throws Exception {
        LinkedHashMap<String, RepoSummary> repos = new LinkedHashMap<>();

        for (String repoId : PRIORITY_REPOS) {
            repos.put(repoId, new RepoSummary(repoId, 0));
        }

        String apiUrl = "https://huggingface.co/api/models?author=litert-community&sort=downloads&direction=-1&limit=" + MAX_REPOS_TO_INSPECT;
        JSONArray array = new JSONArray(httpGet(apiUrl));
        for (int i = 0; i < array.length(); i++) {
            JSONObject model = array.optJSONObject(i);
            if (model == null) continue;
            String id = model.optString("id", "");
            if (!id.startsWith("litert-community/")) continue;
            if (!isUsefulRepo(id)) continue;
            long downloads = model.optLong("downloads", 0);
            repos.put(id, new RepoSummary(id, downloads));
        }

        return new ArrayList<>(repos.values());
    }

    private List<ModelInfo> fetchSmallFilesForRepo(String repoId, long downloads, long deadline) throws Exception {
        Map<String, CandidateFile> files = new LinkedHashMap<>();

        for (CandidateFile file : fetchTreeFiles(repoId)) {
            files.put(file.path, file);
        }
        if (System.currentTimeMillis() < deadline && files.size() < MAX_FILES_PER_REPO) {
            for (CandidateFile file : fetchSiblingFiles(repoId)) {
                files.put(file.path, file);
            }
        }

        // Only probe guessed names for priority repos, and only when the API did not expose files.
        // This avoids dozens of slow HEAD requests.
        if (files.isEmpty() && isPriorityRepo(repoId) && System.currentTimeMillis() < deadline) {
            for (String candidateName : commonCandidateNamesForRepo(repoId)) {
                if (files.size() >= 2) break;
                long size = probeRemoteFileSize(ModelInfo.buildDownloadUrl(repoId, candidateName));
                if (size > 0) files.put(candidateName, new CandidateFile(candidateName, size));
            }
        }

        List<ModelInfo> result = new ArrayList<>();
        for (CandidateFile file : files.values()) {
            if (result.size() >= MAX_FILES_PER_REPO) break;
            if (System.currentTimeMillis() >= deadline) break;
            if (!ModelInfo.isChatModelFile(file.path) && !ModelInfo.isKnownLiteRtFile(file.path)) continue;

            long size = file.sizeBytes;
            if (size <= 0) continue; // Skip unknown sizes. This keeps the under-2GB list honest and fast.
            if (size > MAX_MODEL_BYTES) continue;

            String note = buildNote(repoId, file.path, size, downloads);
            result.add(new ModelInfo(repoId, file.path, size, ModelInfo.buildDownloadUrl(repoId, file.path), note));
        }
        return sortedForDisplay(result);
    }

    private List<CandidateFile> fetchTreeFiles(String repoId) throws Exception {
        List<CandidateFile> result = new ArrayList<>();
        String apiUrl = "https://huggingface.co/api/models/" + repoId + "/tree/main?recursive=1&expand=true";
        JSONArray array = new JSONArray(httpGet(apiUrl));
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "file");
            if (!"file".equalsIgnoreCase(type)) continue;
            String path = item.optString("path", item.optString("rfilename", ""));
            if (path == null || path.trim().isEmpty()) continue;
            if (!ModelInfo.isKnownLiteRtFile(path) && !path.toLowerCase(Locale.US).endsWith(".litertlm")) continue;
            long size = extractSize(item);
            result.add(new CandidateFile(path, size));
        }
        return result;
    }

    private List<CandidateFile> fetchSiblingFiles(String repoId) throws Exception {
        List<CandidateFile> result = new ArrayList<>();
        String apiUrl = "https://huggingface.co/api/models/" + repoId;
        JSONObject json = new JSONObject(httpGet(apiUrl));
        JSONArray siblings = json.optJSONArray("siblings");
        if (siblings == null) return result;

        for (int i = 0; i < siblings.length(); i++) {
            JSONObject file = siblings.optJSONObject(i);
            if (file == null) continue;
            String name = file.optString("rfilename", file.optString("path", ""));
            if (!ModelInfo.isKnownLiteRtFile(name) && !name.toLowerCase(Locale.US).endsWith(".litertlm")) continue;
            long size = extractSize(file);
            result.add(new CandidateFile(name, size));
        }
        return result;
    }

    private List<String> commonCandidateNamesForRepo(String repoId) {
        List<String> names = new ArrayList<>();
        String last = repoId.substring(repoId.lastIndexOf('/') + 1);
        names.add("model.litertlm");
        names.add(last + ".litertlm");
        names.add(last.replace('-', '_').toLowerCase(Locale.US) + ".litertlm");
        for (String fixed : COMMON_LITERTLM_NAMES) names.add(fixed);
        return names;
    }

    private long extractSize(JSONObject object) {
        long size = object.optLong("size", -1);
        if (size > 0) return size;
        JSONObject lfs = object.optJSONObject("lfs");
        if (lfs != null) {
            size = lfs.optLong("size", -1);
            if (size > 0) return size;
        }
        return -1;
    }

    private long probeRemoteFileSize(String downloadUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(HEAD_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(HEAD_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "AndroidLiteRtChatbot/1.2");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) {
                long length = connection.getContentLengthLong();
                if (length > 0) return length;
            }
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
        return -1;
    }

    private String buildNote(String repoId, String fileName, long size, long downloads) {
        StringBuilder note = new StringBuilder();
        String lowerRepo = repoId.toLowerCase(Locale.US);
        String lowerFile = fileName.toLowerCase(Locale.US);

        if (lowerRepo.contains("qwen3-0.6b")) {
            note.append("Best default for your Android web-RAG chat app");
        } else if (lowerRepo.contains("gemma3-1b") || lowerRepo.contains("qwen2.5-1.5b") || lowerRepo.contains("deepseek") || lowerRepo.contains("tinyllama")) {
            note.append("Better answers than tiny models; may be slower but allowed under 2 GB");
        } else if (lowerRepo.contains("embedding")) {
            note.append("Use for semantic retrieval, not as the answer model");
        } else if (lowerRepo.contains("vlm")) {
            note.append("Use for image understanding; text-only models cannot see webpage images");
        } else if (size <= 350L * 1024L * 1024L) {
            note.append("Very small and fast, but weaker answers");
        } else if (size <= 800L * 1024L * 1024L) {
            note.append("Recommended small local chat model");
        } else {
            note.append("Large but still below 2 GB; use on stronger phones");
        }

        if (downloads > 0) note.append(" • downloads: ").append(formatCompact(downloads));
        if (lowerRepo.contains("gemma")) note.append(" • Gemma license may require Hugging Face acceptance");
        if (lowerFile.contains("int4") || lowerFile.contains("q4")) note.append(" • compact INT4/Q4");
        if (lowerFile.contains("int8") || lowerFile.contains("q8")) note.append(" • INT8/Q8, usually higher quality but larger");
        return note.toString();
    }

    private static boolean isUsefulRepo(String repoId) {
        String lower = repoId.toLowerCase(Locale.US);

        // Skip image classifiers/object detectors/segmentation models for the chat model picker.
        if (lower.contains("efficientnet") || lower.contains("mobilenet") || lower.contains("resnet") ||
                lower.contains("squeezenet") || lower.contains("convnext") || lower.contains("vgg") ||
                lower.contains("shufflenet") || lower.contains("inception") || lower.contains("googlenet") ||
                lower.contains("alexnet") || lower.contains("fasterrcnn") || lower.contains("deeplab") ||
                lower.contains("segmentation") || lower.contains("classification") || lower.contains("fcn_")) {
            return false;
        }

        return lower.contains("qwen") || lower.contains("gemma") || lower.contains("smollm") ||
                lower.contains("tinyllama") || lower.contains("deepseek") || lower.contains("phi") ||
                lower.contains("vlm") || lower.contains("embedding") || lower.contains("function") ||
                lower.contains("gecko");
    }

    private static boolean isPriorityRepo(String repoId) {
        for (String priority : PRIORITY_REPOS) {
            if (priority.equalsIgnoreCase(repoId)) return true;
        }
        return false;
    }

    private static List<ModelInfo> sortedForDisplay(List<ModelInfo> models) {
        Collections.sort(models, new Comparator<ModelInfo>() {
            @Override
            public int compare(ModelInfo a, ModelInfo b) {
                int scoreDiff = scoreFor(b) - scoreFor(a);
                if (scoreDiff != 0) return scoreDiff;
                long sizeA = a.sizeBytes <= 0 ? Long.MAX_VALUE : a.sizeBytes;
                long sizeB = b.sizeBytes <= 0 ? Long.MAX_VALUE : b.sizeBytes;
                if (sizeA < sizeB) return -1;
                if (sizeA > sizeB) return 1;
                return a.repoId.compareToIgnoreCase(b.repoId);
            }
        });
        return models;
    }

    private static int scoreFor(ModelInfo model) {
        String repo = model.repoId == null ? "" : model.repoId.toLowerCase(Locale.US);
        String file = model.fileName == null ? "" : model.fileName.toLowerCase(Locale.US);
        int score = 0;

        if (repo.contains("qwen3-0.6b")) score += 1000;
        if (repo.contains("gemma3-1b")) score += 900;
        if (repo.contains("qwen2.5-1.5b")) score += 860;
        if (repo.contains("deepseek-r1-distill-qwen-1.5b")) score += 820;
        if (repo.contains("tinyllama")) score += 760;
        if (repo.contains("qwen2.5-0.5b")) score += 740;
        if (repo.contains("smollm2-360m")) score += 660;
        if (repo.contains("gemma-3-270m")) score += 630;
        if (repo.contains("smollm2-135m") || repo.contains("smollm-135m")) score += 560;
        if (repo.contains("embedding")) score += 460;
        if (repo.contains("vlm")) score += 430;
        if (repo.contains("function")) score += 400;

        if (file.contains("int4") || file.contains("q4")) score += 70;
        if (file.contains("mixed")) score += 40;
        if (file.equals("model.litertlm")) score += 20;
        if (model.sizeBytes > ModelInfo.ONE_GB) score -= 60;
        return score;
    }

    public static List<ModelInfo> fallbackSmallModels() {
        List<ModelInfo> fallback = new ArrayList<>();

        // Reliable under-1GB default from your current setup.
        fallback.add(new ModelInfo(
                "litert-community/Qwen3-0.6B",
                "qwen3_0_6b_mixed_int4.litertlm",
                498L * 1024L * 1024L,
                ModelInfo.buildDownloadUrl("litert-community/Qwen3-0.6B", "qwen3_0_6b_mixed_int4.litertlm"),
                "Recommended default for web-RAG chat; compact mixed INT4"
        ));
        fallback.add(new ModelInfo(
                "litert-community/Qwen3-0.6B",
                "Qwen3-0.6B.litertlm",
                614L * 1024L * 1024L,
                ModelInfo.buildDownloadUrl("litert-community/Qwen3-0.6B", "Qwen3-0.6B.litertlm"),
                "Higher-quality Qwen3 0.6B LiteRT-LM if available"
        ));

        // Good under-2GB answer models. File names may be verified/replaced by remote scan.
        fallback.add(new ModelInfo(
                "litert-community/Gemma3-1B-IT",
                "model.litertlm",
                900L * 1024L * 1024L,
                null,
                "Good answer quality under 2 GB when quantized; Gemma license may require acceptance"
        ));
        fallback.add(new ModelInfo(
                "litert-community/Qwen2.5-1.5B-Instruct",
                "model.litertlm",
                1500L * 1024L * 1024L,
                null,
                "Better than 0.5B/0.6B if a quantized LiteRT-LM file is available"
        ));
        fallback.add(new ModelInfo(
                "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
                "model.litertlm",
                1500L * 1024L * 1024L,
                null,
                "Reasoning-oriented; slower; use only if quantized file fits your phone"
        ));
        fallback.add(new ModelInfo(
                "litert-community/TinyLlama-1.1B-Chat-v1.0",
                "model.litertlm",
                1100L * 1024L * 1024L,
                null,
                "Older but useful chat fallback under 2 GB"
        ));
        fallback.add(new ModelInfo(
                "litert-community/Qwen2.5-0.5B-Instruct",
                "model.litertlm",
                620L * 1024L * 1024L,
                null,
                "Small Qwen instruct fallback"
        ));
        fallback.add(new ModelInfo(
                "litert-community/SmolLM2-360M-Instruct",
                "model.litertlm",
                374L * 1024L * 1024L,
                null,
                "Very fast; weaker than Qwen/Gemma for RAG answers"
        ));
        fallback.add(new ModelInfo(
                "litert-community/gemma-3-270m-it",
                "gemma3-270m-it-q8.litertlm",
                304L * 1024L * 1024L,
                null,
                "Small Gemma model; fast; Gemma license may require acceptance"
        ));
        fallback.add(new ModelInfo(
                "litert-community/SmolLM2-135M-Instruct",
                "model.litertlm",
                180L * 1024L * 1024L,
                null,
                "Tiny and fast, but answer quality is limited"
        ));
        fallback.add(new ModelInfo(
                "litert-community/SmolLM-135M-Instruct",
                "model.litertlm",
                160L * 1024L * 1024L,
                null,
                "Tiny and fast, but answer quality is limited"
        ));

        // Optional helper models.
        fallback.add(new ModelInfo(
                "litert-community/embeddinggemma-300m",
                "model.litertlm",
                350L * 1024L * 1024L,
                null,
                "Embedding/RAG helper, not an answer model"
        ));
        fallback.add(new ModelInfo(
                "litert-community/FastVLM-0.5B",
                "model.litertlm",
                700L * 1024L * 1024L,
                null,
                "Optional image understanding model"
        ));
        fallback.add(new ModelInfo(
                "litert-community/SmolVLM-256M-Instruct",
                "model.litertlm",
                450L * 1024L * 1024L,
                null,
                "Optional small image understanding model"
        ));
        fallback.add(new ModelInfo(
                "litert-community/functiongemma-270m-ft-mobile-actions",
                "mobile_actions_q8_ekv1024.litertlm",
                289L * 1024L * 1024L,
                null,
                "Function/mobile-action model, not the best general RAG answer model"
        ));

        return sortedForDisplay(fallback);
    }

    private void addFallbackMissing(Map<String, ModelInfo> models) {
        for (ModelInfo info : fallbackSmallModels()) {
            models.put(info.repoId + "/" + info.fileName, info);
        }
    }

    private static String iconFor(ModelInfo model) {
        String repo = model.repoId == null ? "" : model.repoId.toLowerCase(Locale.US);
        if (repo.contains("qwen3-0.6b")) return "⭐";
        if (repo.contains("gemma3-1b") || repo.contains("qwen2.5-1.5b") || repo.contains("deepseek") || repo.contains("tinyllama")) return "✅";
        if (repo.contains("embedding")) return "🔎";
        if (repo.contains("vlm")) return "🖼️";
        if (repo.contains("function")) return "⚙️";
        return "•";
    }

    private static String useCaseFor(ModelInfo model) {
        String repo = model.repoId == null ? "" : model.repoId.toLowerCase(Locale.US);
        if (repo.contains("embedding")) return "RAG embedding / similarity search";
        if (repo.contains("vlm")) return "Image understanding";
        if (repo.contains("function")) return "Mobile actions / function calling";
        if (repo.contains("deepseek")) return "Text chat with reasoning style";
        return "Text chat and web-RAG answering";
    }

    private static String shortName(String repoId) {
        if (repoId == null) return "Unknown model";
        int slash = repoId.lastIndexOf('/');
        return slash >= 0 ? repoId.substring(slash + 1) : repoId;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "unknown";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024.0) return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        return String.format(Locale.US, "%.0f MB", mb);
    }

    private String formatCompact(long value) {
        if (value >= 1_000_000) return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.US, "%.1fk", value / 1_000.0);
        return String.valueOf(value);
    }

    private static String shortenMiddle(String value, int max) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() <= max) return clean;
        int side = Math.max(8, (max - 1) / 2);
        return clean.substring(0, side) + "…" + clean.substring(clean.length() - side);
    }

    private String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "AndroidLiteRtChatbot/1.2");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readStream(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Hugging Face API error " + code + ": " + body);
        }
        return body;
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
            if (builder.length() > 1_500_000) break; // Safety limit for mobile memory.
        }
        reader.close();
        return builder.toString();
    }

    private static class RepoSummary {
        final String repoId;
        final long downloads;

        RepoSummary(String repoId, long downloads) {
            this.repoId = repoId;
            this.downloads = downloads;
        }
    }

    private static class CandidateFile {
        final String path;
        final long sizeBytes;

        CandidateFile(String path, long sizeBytes) {
            this.path = path;
            this.sizeBytes = sizeBytes;
        }
    }
}
