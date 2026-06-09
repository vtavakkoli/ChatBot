package com.example.chatbot;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RepositoryActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView statusText;
    private TextView selectedText;
    private TextView countText;
    private ProgressBar repositoryProgressBar;
    private ProgressBar downloadProgressBar;
    private Button refreshButton;
    private Button downloadButton;
    private Button clearSearchButton;
    private EditText searchInput;
    private ListView repositoryListView;

    private final List<ModelInfo> models = new ArrayList<>();
    private final List<ModelInfo> filteredModels = new ArrayList<>();
    private ModelRepositoryAdapter adapter;
    private ModelInfo selectedModel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_repository);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.repositoryRoot), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        statusText = findViewById(R.id.repositoryStatusText);
        selectedText = findViewById(R.id.selectedModelText);
        countText = findViewById(R.id.repositoryCountText);
        repositoryProgressBar = findViewById(R.id.repositoryProgressBar);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        refreshButton = findViewById(R.id.refreshRepositoryButton);
        downloadButton = findViewById(R.id.downloadSelectedButton);
        clearSearchButton = findViewById(R.id.clearSearchButton);
        searchInput = findViewById(R.id.modelSearchInput);
        repositoryListView = findViewById(R.id.repositoryListView);

        adapter = new ModelRepositoryAdapter(this);
        repositoryListView.setAdapter(adapter);
        repositoryListView.setDivider(null);
        repositoryListView.setDividerHeight(10);

        repositoryListView.setOnItemClickListener((parent, view, position, id) -> {
            selectedModel = filteredModels.get(position);
            selectedText.setText("Selected:\n" + selectedModel.repoId + "\n" + selectedModel.fileName + " • " + ModelInfo.formatSize(selectedModel.sizeBytes));
            downloadButton.setEnabled(true);
            applyFilter();
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        refreshButton.setOnClickListener(view -> refreshRepository());
        clearSearchButton.setOnClickListener(view -> searchInput.setText(""));
        downloadButton.setOnClickListener(view -> downloadSelectedModel());
        downloadButton.setEnabled(false);

        loadFallbackImmediately();
        refreshRepository();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadFallbackImmediately() {
        models.clear();
        models.addAll(HuggingFaceModelRepositoryClient.fallbackSmallModels());
        renderModels("Starter catalog loaded. Refresh from Hugging Face to find more small LiteRT-LM chat models.");
    }

    private void refreshRepository() {
        setRepositoryLoading(true);
        statusText.setText("Reading Hugging Face LiteRT Community repositories. The app now inspects model file trees and keeps .litertlm files below 1 GB.");
        executor.execute(() -> {
            List<ModelInfo> fetched;
            String message;
            try {
                fetched = new HuggingFaceModelRepositoryClient().fetchSmallLiteRtLmModels();
                message = "Live repository loaded. Showing chat-compatible .litertlm files below 1 GB. Larger models such as 2B, 4B, 12B are intentionally skipped for phone storage/RAM.";
            } catch (Exception error) {
                fetched = HuggingFaceModelRepositoryClient.fallbackSmallModels();
                message = "Could not refresh live Hugging Face list. Showing starter catalog. Reason: " + safeMessage(error);
            }
            List<ModelInfo> finalFetched = fetched;
            String finalMessage = message;
            mainHandler.post(() -> {
                models.clear();
                models.addAll(finalFetched);
                renderModels(finalMessage);
                setRepositoryLoading(false);
            });
        });
    }

    private void renderModels(String message) {
        statusText.setText(message + "\nStorage:\n" + LocalModelStore.modelsDirectory(this).getAbsolutePath());
        selectedModel = null;
        selectedText.setText("Select a model card, then tap Download model.");
        downloadButton.setEnabled(false);
        applyFilter();
    }

    private void applyFilter() {
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.US);
        filteredModels.clear();
        for (ModelInfo model : models) {
            String haystack = (model.repoId + " " + model.fileName + " " + model.note).toLowerCase(Locale.US);
            if (query.isEmpty() || haystack.contains(query)) {
                filteredModels.add(model);
            }
        }
        adapter.setModels(filteredModels, selectedModel);
        String text = filteredModels.size() + " model" + (filteredModels.size() == 1 ? "" : "s") + " shown";
        if (!query.isEmpty()) text += " for \"" + query + "\"";
        countText.setText(text);
    }

    private void downloadSelectedModel() {
        if (selectedModel == null) {
            statusText.setText("Please select a model first.");
            return;
        }
        if (!selectedModel.isChatCompatible()) {
            statusText.setText("This file is not a .litertlm chat model. Select a .litertlm model for this app.");
            return;
        }

        File target = LocalModelStore.targetFile(this, selectedModel);
        setDownloadLoading(true);
        statusText.setText("Downloading:\n" + selectedModel.repoId + "\n" + selectedModel.fileName + "\nTo:\n" + target.getAbsolutePath());

        executor.execute(() -> new ModelDownloader().download(selectedModel, target, new ModelDownloader.Callback() {
            @Override
            public void onProgress(int percent, long downloadedBytes, long totalBytes) {
                mainHandler.post(() -> {
                    downloadProgressBar.setIndeterminate(totalBytes <= 0);
                    downloadProgressBar.setProgress(percent);
                    String totalText = totalBytes > 0 ? ModelInfo.formatSize(totalBytes) : "unknown";
                    statusText.setText("Downloading " + percent + "%\n" +
                            ModelInfo.formatSize(downloadedBytes) + " / " + totalText + "\n" +
                            selectedModel.fileName);
                });
            }

            @Override
            public void onDone(File file) {
                try {
                    LocalModelStore.saveDownloadedModel(RepositoryActivity.this, selectedModel, file);
                } catch (Exception ignored) {
                    // The file exists; metadata is helpful but not mandatory because ChatActivity also scans the directory.
                }
                mainHandler.post(() -> {
                    setDownloadLoading(false);
                    statusText.setText("Download finished.\nSaved model:\n" + file.getAbsolutePath() +
                            "\n\nOpen Local Model Chat and select this model.");
                });
            }

            @Override
            public void onError(Exception error) {
                mainHandler.post(() -> {
                    setDownloadLoading(false);
                    statusText.setText("Download failed:\n" + safeMessage(error) +
                            "\n\nFor gated Gemma repositories, first accept the license on Hugging Face, then try again. Or choose Qwen/SmolLM.");
                });
            }
        }));
    }

    private void setRepositoryLoading(boolean loading) {
        repositoryProgressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!loading);
    }

    private void setDownloadLoading(boolean loading) {
        downloadProgressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        downloadProgressBar.setProgress(0);
        downloadButton.setEnabled(!loading && selectedModel != null);
        refreshButton.setEnabled(!loading);
        searchInput.setEnabled(!loading);
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
