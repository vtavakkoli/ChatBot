package com.example.chatbot;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.util.Locale;

public class ModelInfo {
    public static final long ONE_GB = 1024L * 1024L * 1024L;

    public final String repoId;
    public final String fileName;
    public final long sizeBytes;
    public final String downloadUrl;
    public final String note;
    public final String localPath;

    public ModelInfo(String repoId, String fileName, long sizeBytes, String downloadUrl, String note) {
        this(repoId, fileName, sizeBytes, downloadUrl, note, null);
    }

    public ModelInfo(String repoId, String fileName, long sizeBytes, String downloadUrl, String note, String localPath) {
        this.repoId = repoId == null ? "" : repoId;
        this.fileName = fileName == null ? "" : fileName;
        this.sizeBytes = sizeBytes;
        this.downloadUrl = (downloadUrl == null || downloadUrl.trim().isEmpty()) ? buildDownloadUrl(this.repoId, this.fileName) : downloadUrl;
        this.note = note == null ? "" : note;
        this.localPath = localPath;
    }

    public static String buildDownloadUrl(String repoId, String fileName) {
        String encodedFile = encodePath(fileName);
        return "https://huggingface.co/" + repoId + "/resolve/main/" + encodedFile + "?download=true";
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) builder.append('/');
            try {
                builder.append(URLEncoder.encode(parts[i], "UTF-8"));
            } catch (Exception ignored) {
                builder.append(parts[i]);
            }
        }
        return builder.toString().replace("+", "%20");
    }

    public String displayName() {
        String sizeText = sizeBytes > 0 ? formatSize(sizeBytes) : "unknown size";
        String base = repoId + "\n" + fileName + "  •  " + sizeText;
        if (!note.isEmpty()) {
            base += "\n" + note;
        }
        if (localPath != null && !localPath.isEmpty()) {
            base += "\nLocal: " + localPath;
        }
        return base;
    }

    public String title() {
        if (repoId == null || repoId.trim().isEmpty()) return "Local model";
        return repoId;
    }

    public String subtitle() {
        String size = sizeBytes > 0 ? formatSize(sizeBytes) : "unknown size";
        return fileName + "  •  " + size;
    }

    public String badgeText() {
        if (isChatModelFile(fileName)) return "CHAT";
        return extension().toUpperCase(Locale.US);
    }

    public String extension() {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "MODEL";
        return fileName.substring(dot + 1).toLowerCase(Locale.US);
    }

    public boolean isChatCompatible() {
        return isChatModelFile(fileName);
    }

    public String compatibilityText() {
        if (isChatCompatible()) return "Ready for the local chat activity";
        return "Downloadable model file; not used by the LiteRT-LM chat loader";
    }

    public String shortName() {
        return fileName + " (" + formatSize(sizeBytes) + ")";
    }

    public String safeFolderName() {
        return repoId.replace('/', '_').replace(':', '_');
    }

    public String defaultLocalFileName() {
        return safeFolderName() + "__" + new File(fileName).getName();
    }

    public static boolean isChatModelFile(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".litertlm");
    }

    public static boolean isKnownLiteRtFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".litertlm") || lower.endsWith(".task") || lower.endsWith(".tflite");
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "unknown";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 1024.0) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("repoId", repoId);
        json.put("fileName", fileName);
        json.put("sizeBytes", sizeBytes);
        json.put("downloadUrl", downloadUrl);
        json.put("note", note);
        json.put("localPath", localPath == null ? "" : localPath);
        return json;
    }

    public static ModelInfo fromJson(JSONObject json) {
        return new ModelInfo(
                json.optString("repoId"),
                json.optString("fileName"),
                json.optLong("sizeBytes", 0),
                json.optString("downloadUrl"),
                json.optString("note"),
                json.optString("localPath")
        );
    }
}
