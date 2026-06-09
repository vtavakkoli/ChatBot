package com.example.chatbot;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ModelDownloader {
    public interface Callback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onDone(File file);
        void onError(Exception error);
    }

    public void download(ModelInfo modelInfo, File targetFile, Callback callback) {
        File partFile = new File(targetFile.getAbsolutePath() + ".part");
        HttpURLConnection connection = null;
        try {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (partFile.exists()) partFile.delete();

            connection = (HttpURLConnection) new URL(modelInfo.downloadUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "AndroidLiteRtChatbot/1.0");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Download failed with HTTP " + code + ". If this is a gated Gemma model, accept the license on Hugging Face first or choose Qwen3-0.6B.");
            }

            long total = connection.getContentLengthLong();
            BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
            FileOutputStream output = new FileOutputStream(partFile, false);
            byte[] buffer = new byte[1024 * 256];
            long downloaded = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                downloaded += read;
                int percent = total > 0 ? (int) Math.min(100, (downloaded * 100L) / total) : 0;
                callback.onProgress(percent, downloaded, total);
            }
            output.flush();
            output.close();
            input.close();

            if (targetFile.exists()) targetFile.delete();
            if (!partFile.renameTo(targetFile)) {
                throw new IllegalStateException("Could not move temporary download to final model file.");
            }
            callback.onProgress(100, targetFile.length(), targetFile.length());
            callback.onDone(targetFile);
        } catch (Exception error) {
            if (partFile.exists()) partFile.delete();
            callback.onError(error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
