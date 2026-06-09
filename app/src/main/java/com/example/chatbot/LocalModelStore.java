package com.example.chatbot;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LocalModelStore {
    private static final String CATALOG_FILE = "local_models.json";

    public static File modelsDirectory(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = context.getFilesDir();
        File models = new File(dir, "models");
        if (!models.exists()) models.mkdirs();
        return models;
    }

    public static File metadataFile(Context context) {
        return new File(modelsDirectory(context), CATALOG_FILE);
    }

    public static File targetFile(Context context, ModelInfo modelInfo) {
        return new File(modelsDirectory(context), modelInfo.defaultLocalFileName());
    }

    public static List<ModelInfo> listLocalModels(Context context) {
        List<ModelInfo> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            File metadata = metadataFile(context);
            if (metadata.exists()) {
                byte[] data = readAll(metadata);
                JSONArray array = new JSONArray(new String(data, StandardCharsets.UTF_8));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject json = array.optJSONObject(i);
                    if (json == null) continue;
                    ModelInfo info = ModelInfo.fromJson(json);
                    if (info.localPath != null && !info.localPath.isEmpty()) {
                        File local = new File(info.localPath);
                        if (local.exists() && isLoadableLiteRtModel(local.getName()) && seen.add(local.getAbsolutePath())) {
                            result.add(info);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // If metadata is corrupted, fall back to scanning files.
        }

        scanDirectory(modelsDirectory(context), result, seen);
        return result;
    }

    private static void scanDirectory(File dir, List<ModelInfo> result, Set<String> seen) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanDirectory(file, result, seen);
            } else if (file.isFile() && isLoadableLiteRtModel(file.getName()) && seen.add(file.getAbsolutePath())) {
                result.add(new ModelInfo("local", file.getName(), file.length(), "", "Found in local repository", file.getAbsolutePath()));
            }
        }
    }

    public static boolean isLoadableLiteRtModel(String name) {
        return ModelInfo.isChatModelFile(name);
    }

    public static void saveDownloadedModel(Context context, ModelInfo source, File localFile) throws Exception {
        List<ModelInfo> current = listLocalModels(context);
        List<ModelInfo> updated = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        ModelInfo downloaded = new ModelInfo(
                source.repoId,
                source.fileName,
                localFile.length() > 0 ? localFile.length() : source.sizeBytes,
                source.downloadUrl,
                source.note,
                localFile.getAbsolutePath()
        );
        updated.add(downloaded);
        seen.add(localFile.getAbsolutePath());

        for (ModelInfo info : current) {
            if (info.localPath != null && seen.add(info.localPath)) {
                updated.add(info);
            }
        }

        JSONArray array = new JSONArray();
        for (ModelInfo info : updated) {
            array.put(info.toJson());
        }
        writeAll(metadataFile(context), array.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream inputStream = new FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            while (offset < buffer.length) {
                int read = inputStream.read(buffer, offset, buffer.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return buffer;
        } finally {
            inputStream.close();
        }
    }

    private static void writeAll(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream outputStream = new FileOutputStream(file, false);
        try {
            outputStream.write(data);
        } finally {
            outputStream.close();
        }
    }
}
