package ai.chatbot.litert;

import android.content.Context;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.LogSeverity;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.Collections;

/**
 * Runs a local .litertlm model on Android with Google AI Edge LiteRT-LM.
 *
 * Required dependency in the app module:
 * implementation "com.google.ai.edge.litertlm:litertlm-android:latest.release"
 *
 * Recommended model for small devices:
 * litert-community/Qwen3-0.6B / qwen3_0_6b_mixed_int4.litertlm
 */
public class LiteRtGemmaClient {

    private Engine engine;
    private String loadedModelPath;
    private boolean loadedGpuMode;

    public synchronized void load(
            Context context,
            String modelPath,
            boolean useGpu
    ) throws Exception {
        ensureEngine(context, modelPath, useGpu);
    }

    public synchronized String generate(
            Context context,
            String modelPath,
            String prompt,
            boolean useGpu
    ) throws Exception {
        ensureEngine(context, modelPath, useGpu);

        ConversationConfig conversationConfig = new ConversationConfig(
                Contents.Companion.of(
                        "You are a helpful assistant. Use the provided internet search context. " +
                                "Do not invent sources. Keep answers clear and short."
                ),
                Collections.emptyList(),
                Collections.emptyList(),
                new SamplerConfig(40, 0.90, 0.20, 0),
                true,
                null,
                Collections.emptyMap(),
                null
        );

        Conversation conversation = null;
        try {
            conversation = engine.createConversation(conversationConfig);
            Message message = conversation.sendMessage(prompt, Collections.emptyMap());
            String text = message.toString();
            return text == null ? "" : text.trim();
        } finally {
            if (conversation != null) {
                try {
                    conversation.close();
                } catch (Exception ignored) {
                    // Conversation may already be closed by the native runtime.
                }
            }
        }
    }

    private synchronized void ensureEngine(Context context, String modelPath, boolean useGpu) {
        String cleanPath = modelPath == null ? "" : modelPath.trim();
        if (cleanPath.isEmpty()) {
            throw new IllegalArgumentException("Model path is empty. Download and select a .litertlm model first.");
        }

        File modelFile = new File(cleanPath);
        if (!modelFile.exists()) {
            throw new IllegalArgumentException(
                    "Model file not found: " + cleanPath + "\n" +
                            "Download a .litertlm model first from the Model Repository screen."
            );
        }

        if (engine != null && cleanPath.equals(loadedModelPath) && loadedGpuMode == useGpu) {
            return;
        }

        close();

        Engine.Companion.setNativeMinLogSeverity(LogSeverity.ERROR);

        Backend backend = useGpu ? new Backend.GPU() : new Backend.CPU(null);
        String cacheDir = new File(context.getCacheDir(), "litertlm-cache").getAbsolutePath();

        EngineConfig engineConfig = new EngineConfig(
                cleanPath,
                backend,
                null,
                null,
                null,
                null,
                cacheDir
        );

        engine = new Engine(engineConfig);
        engine.initialize();
        loadedModelPath = cleanPath;
        loadedGpuMode = useGpu;
    }

    public synchronized void close() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception ignored) {
                // Ignore close errors during Activity destruction or model reload.
            }
        }
        engine = null;
        loadedModelPath = null;
        loadedGpuMode = false;
    }
}
