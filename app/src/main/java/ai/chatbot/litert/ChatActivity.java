package ai.chatbot.litert;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "local_model_chat";
    private static final String KEY_LAST_MODEL_PATH = "last_model_path";
    private static final int MAX_LOCAL_PROMPT_CHARS = 3200;
    private static final int MAX_SEARCH_CONTEXT_CHARS = 2000;
    private static final int MAX_RETRY_CONTEXT_CHARS = 900;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LiteRtGemmaClient llmClient = new LiteRtGemmaClient();
    private final InternetSearchClient searchClient = new InternetSearchClient();

    private Spinner localModelSpinner;
    private ArrayAdapter<String> modelAdapter;
    private TextView modelStatusText;
    private LinearLayout messageContainer;
    private EditText questionInput;
    private Button refreshLocalButton;
    private Button loadSelectedButton;
    private Button sendButton;
    private ProgressBar progressBar;
    private ScrollView chatScrollView;
    private CheckBox searchCheckBox;
    private CheckBox gpuCheckBox;
    private CheckBox showReasoningCheckBox;
    private CheckBox formatCodeCheckBox;

    private final List<ModelInfo> localModels = new ArrayList<>();
    private String loadedModelPath = "";

    private static class BotOutput {
        final String answer;
        final InternetSearchClient.SearchResult searchResult;

        BotOutput(String answer, InternetSearchClient.SearchResult searchResult) {
            this.answer = answer;
            this.searchResult = searchResult;
        }
    }

    private static class ChatParts {
        final String thinking;
        final String answer;

        ChatParts(String thinking, String answer) {
            this.thinking = thinking;
            this.answer = answer;
        }
    }

    private static class MessageSegment {
        final boolean code;
        final String language;
        final String text;

        MessageSegment(boolean code, String language, String text) {
            this.code = code;
            this.language = language == null ? "" : language.trim();
            this.text = text == null ? "" : text;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_chat);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        localModelSpinner = findViewById(R.id.localModelSpinner);
        modelStatusText = findViewById(R.id.modelStatusText);
        messageContainer = findViewById(R.id.messageContainer);
        questionInput = findViewById(R.id.questionInput);
        refreshLocalButton = findViewById(R.id.refreshLocalButton);
        loadSelectedButton = findViewById(R.id.loadSelectedButton);
        sendButton = findViewById(R.id.sendButton);
        progressBar = findViewById(R.id.progressBar);
        chatScrollView = findViewById(R.id.chatScrollView);
        searchCheckBox = findViewById(R.id.searchCheckBox);
        gpuCheckBox = findViewById(R.id.gpuCheckBox);
        showReasoningCheckBox = findViewById(R.id.showReasoningCheckBox);
        formatCodeCheckBox = findViewById(R.id.formatCodeCheckBox);

        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        localModelSpinner.setAdapter(modelAdapter);

        refreshLocalButton.setOnClickListener(view -> loadLocalModelList());
        loadSelectedButton.setOnClickListener(view -> loadSelectedModel());
        sendButton.setOnClickListener(view -> askQuestion());

        questionInput.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                askQuestion();
                return true;
            }
            return false;
        });

        addBotMessage("Open Model Repository, download a small .litertlm model, then return here and load it. For current questions, keep Agentic internet search checked so the app opens web links and adds their content to the model context.", null);
        loadLocalModelList();
    }

    @Override
    protected void onDestroy() {
        llmClient.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void loadLocalModelList() {
        localModels.clear();
        localModels.addAll(LocalModelStore.listLocalModels(this));
        modelAdapter.clear();
        for (ModelInfo model : localModels) {
            modelAdapter.add(model.shortName() + "\n" + model.localPath);
        }
        modelAdapter.notifyDataSetChanged();

        if (localModels.isEmpty()) {
            modelStatusText.setText("No local .litertlm model found. Open Model Repository and download a model below 1 GB first.\nFolder:\n" +
                    LocalModelStore.modelsDirectory(this).getAbsolutePath());
            loadSelectedButton.setEnabled(false);
            sendButton.setEnabled(false);
            return;
        }

        loadSelectedButton.setEnabled(true);
        sendButton.setEnabled(true);
        selectLastModelIfAvailable();
        modelStatusText.setText("Select a local model and tap Load selected model.\nLocal repository:\n" +
                LocalModelStore.modelsDirectory(this).getAbsolutePath());
    }

    private void selectLastModelIfAvailable() {
        String lastPath = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_MODEL_PATH, "");
        if (lastPath == null || lastPath.isEmpty()) return;
        for (int i = 0; i < localModels.size(); i++) {
            if (lastPath.equals(localModels.get(i).localPath)) {
                localModelSpinner.setSelection(i);
                return;
            }
        }
    }

    private ModelInfo selectedLocalModel() {
        int index = localModelSpinner.getSelectedItemPosition();
        if (index < 0 || index >= localModels.size()) return null;
        return localModels.get(index);
    }

    private void loadSelectedModel() {
        ModelInfo model = selectedLocalModel();
        if (model == null || model.localPath == null) {
            modelStatusText.setText("No local model selected.");
            return;
        }
        File file = new File(model.localPath);
        if (!file.exists()) {
            modelStatusText.setText("Selected model file does not exist anymore:\n" + model.localPath);
            loadLocalModelList();
            return;
        }

        setLoading(true);
        modelStatusText.setText("Loading model from filesystem:\n" + model.localPath);
        boolean useGpu = gpuCheckBox.isChecked();
        executor.execute(() -> {
            String message;
            try {
                llmClient.load(getApplicationContext(), model.localPath, useGpu);
                loadedModelPath = model.localPath;
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST_MODEL_PATH, loadedModelPath)
                        .apply();
                message = "Model loaded successfully:\n" + model.fileName;
            } catch (Exception error) {
                loadedModelPath = "";
                message = "Could not load model:\n" + safeMessage(error);
            }
            String finalMessage = message;
            mainHandler.post(() -> {
                modelStatusText.setText(finalMessage);
                setLoading(false);
            });
        });
    }

    private void askQuestion() {
        String question = questionInput.getText().toString().trim();
        if (question.isEmpty()) {
            questionInput.setError("Please write a question");
            return;
        }
        ModelInfo selected = selectedLocalModel();
        if (selected == null || selected.localPath == null || !new File(selected.localPath).exists()) {
            addBotMessage("No local model is available. Open Model Repository and download one first.", null);
            return;
        }

        String modelPath = selected.localPath;
        boolean useGpu = gpuCheckBox.isChecked();
        boolean useSearch = searchCheckBox.isChecked();
        questionInput.setText("");
        addUserMessage(question);
        setLoading(true);

        executor.execute(() -> {
            BotOutput output;
            try {
                if (!modelPath.equals(loadedModelPath)) {
                    llmClient.load(getApplicationContext(), modelPath, useGpu);
                    loadedModelPath = modelPath;
                }

                InternetSearchClient.SearchResult searchResult = useSearch
                        ? searchClient.search(question)
                        : new InternetSearchClient.SearchResult("Internet search disabled by user.", "Internet search disabled.", false);

                String prompt = buildPrompt(question, searchResult, useSearch);
                String answer;
                try {
                    answer = llmClient.generate(getApplicationContext(), modelPath, prompt, useGpu);
                } catch (Exception firstError) {
                    if (isInputTooLongError(firstError)) {
                        String retryPrompt = buildRetryPrompt(question, searchResult, useSearch);
                        answer = llmClient.generate(getApplicationContext(), modelPath, retryPrompt, useGpu);
                        if (answer != null && !answer.trim().isEmpty()) {
                            answer = answer.trim() + "\n\n_Note: Web context was automatically compressed because this local model has a small input limit._";
                        }
                    } else {
                        throw firstError;
                    }
                }
                if (answer == null || answer.trim().isEmpty()) {
                    answer = "The local model returned an empty answer.";
                    if (searchResult.hasUsefulResults) answer += "\n\nSearch results were found, but the local model could not answer. Try a shorter question or a model with a larger context window.";
                }
                output = new BotOutput(answer.trim(), searchResult);
            } catch (Exception error) {
                output = new BotOutput("Could not answer with the selected local model.\nReason: " + friendlyModelError(error), null);
            }

            BotOutput finalOutput = output;
            mainHandler.post(() -> {
                addBotMessage(finalOutput.answer, finalOutput.searchResult);
                setLoading(false);
            });
        });
    }

    private String buildPrompt(String question, InternetSearchClient.SearchResult searchResult, boolean useSearch) {
        if (useSearch) {
            String context = limitText(searchResult.contextForPrompt, MAX_SEARCH_CONTEXT_CHARS);
            String prompt = "Answer using the compressed agentic web context. " +
                    "The app searched DuckDuckGo, checked up to 10 links, and kept only relevant snippets because the local model has a small input limit. " +
                    "Use source labels exactly like [S1], [S2]. If evidence is weak, say so. " +
                    "Do not output <think> tags. Return only the final answer. " +
                    "Use fenced Markdown code blocks for code. Keep it short.\n\n" +
                    "WEB CONTEXT:\n" + context + "\n\n" +
                    "QUESTION:\n" + limitText(question, 500) + "\n\n" +
                    "FINAL ANSWER:";
            return limitText(prompt, MAX_LOCAL_PROMPT_CHARS);
        }
        String prompt = "Answer clearly and shortly. Do not output <think> tags. " +
                "Use fenced Markdown code blocks for code.\n\n" +
                "QUESTION:\n" + limitText(question, 800) + "\n\nFINAL ANSWER:";
        return limitText(prompt, MAX_LOCAL_PROMPT_CHARS);
    }

    private String buildRetryPrompt(String question, InternetSearchClient.SearchResult searchResult, boolean useSearch) {
        if (useSearch) {
            return "Answer shortly using this very small web context. Cite [S1] if useful. " +
                    "No <think> tags.\n\n" +
                    limitText(searchResult.contextForPrompt, MAX_RETRY_CONTEXT_CHARS) +
                    "\n\nQuestion: " + limitText(question, 350) +
                    "\nAnswer:";
        }
        return "Answer shortly. No <think> tags.\nQuestion: " + limitText(question, 600) + "\nAnswer:";
    }

    private String limitText(String value, int maxChars) {
        if (value == null) return "";
        String clean = value.replaceAll("\\s+", " ").trim();
        if (maxChars <= 0 || clean.length() <= maxChars) return clean;
        return clean.substring(0, Math.max(1, maxChars - 35)).trim() + "\n[truncated to fit model input]";
    }

    private void addUserMessage(String message) {
        addMessageCard("You", message, R.drawable.bg_user_message, Gravity.END, false);
    }

    private void addBotMessage(String rawMessage, InternetSearchClient.SearchResult searchResult) {
        ChatParts parts = splitThinking(rawMessage == null ? "" : rawMessage);
        if (!parts.thinking.isEmpty() && showReasoningCheckBox.isChecked()) {
            addMessageCard("Model thinking", parts.thinking, R.drawable.bg_think_message, Gravity.START, true);
        }
        String answer = parts.answer.isEmpty() ? rawMessage : parts.answer;
        addMessageCard("Bot", answer, R.drawable.bg_bot_message, Gravity.START, false, searchResult);

        if (searchResult != null) {
            String title = searchResult.hasUsefulResults ? "Web sources used" : "Web search status";
            addMessageCard(title, searchResult.displayText, R.drawable.bg_sources_message, Gravity.START, true, searchResult);
        }
    }

    private void addMessageCard(String label, String body, int backgroundRes, int gravity, boolean secondary) {
        addMessageCard(label, body, backgroundRes, gravity, secondary, null);
    }

    private void addMessageCard(String label, String body, int backgroundRes, int gravity, boolean secondary, InternetSearchClient.SearchResult searchResult) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, dp(6), 0, dp(6));
        row.setLayoutParams(rowParams);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(ContextCompat.getDrawable(this, backgroundRes));
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        if (gravity == Gravity.END) {
            bubbleParams.setMargins(dp(32), 0, 0, 0);
        } else {
            bubbleParams.setMargins(0, 0, dp(32), 0);
        }
        bubble.setLayoutParams(bubbleParams);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(secondary ? 12 : 13);
        title.setTextColor(ContextCompat.getColor(this, secondary ? R.color.text_secondary : R.color.text_primary));
        bubble.addView(title);

        String safeBody = body == null || body.trim().isEmpty() ? "No content." : body.trim();
        boolean isThinking = label.toLowerCase(Locale.US).contains("thinking");
        boolean shouldFormatCode = formatCodeCheckBox == null || formatCodeCheckBox.isChecked();
        if (shouldFormatCode) {
            renderFormattedMessage(bubble, safeBody, secondary, isThinking, searchResult);
        } else {
            TextView text = createPlainTextView(safeBody, secondary, isThinking, searchResult);
            bubble.addView(text);
        }

        row.addView(bubble);
        messageContainer.addView(row);
        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void renderFormattedMessage(LinearLayout bubble, String body, boolean secondary, boolean isThinking, InternetSearchClient.SearchResult searchResult) {
        List<MessageSegment> segments = splitCodeBlocks(body);
        boolean added = false;
        for (MessageSegment segment : segments) {
            String text = segment.text == null ? "" : segment.text.trim();
            if (text.isEmpty()) continue;
            added = true;
            if (segment.code) {
                bubble.addView(createCodeBlockView(text, segment.language));
            } else {
                bubble.addView(createPlainTextView(text, secondary, isThinking, searchResult));
            }
        }
        if (!added) {
            bubble.addView(createPlainTextView("No content.", secondary, isThinking, searchResult));
        }
    }

    private TextView createPlainTextView(String body, boolean secondary, boolean isThinking, InternetSearchClient.SearchResult searchResult) {
        TextView text = new TextView(this);
        text.setTextSize(secondary ? 13 : 15);
        text.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        text.setLinkTextColor(ContextCompat.getColor(this, R.color.primary));
        text.setLineSpacing(0, 1.08f);
        if (isThinking) {
            text.setTypeface(Typeface.MONOSPACE);
        }

        SpannableStringBuilder richText = applyRichTextSpans(body, searchResult);
        text.setText(richText);
        if (hasClickableLinks(richText)) {
            text.setMovementMethod(LinkMovementMethod.getInstance());
            text.setLinksClickable(true);
            // Selectable TextViews often consume link taps. Keep linked source cards tap-friendly.
            text.setTextIsSelectable(false);
        } else {
            text.setTextIsSelectable(true);
        }
        return text;
    }

    private View createCodeBlockView(String code, String language) {
        LinearLayout codeBox = new LinearLayout(this);
        codeBox.setOrientation(LinearLayout.VERTICAL);
        codeBox.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_code_block));
        codeBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        boxParams.setMargins(0, dp(8), 0, dp(8));
        codeBox.setLayoutParams(boxParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView langView = new TextView(this);
        String lang = language == null || language.trim().isEmpty() ? "code" : language.trim();
        langView.setText(lang);
        langView.setTextColor(ContextCompat.getColor(this, R.color.code_header_text));
        langView.setTextSize(12);
        langView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams langParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        langView.setLayoutParams(langParams);

        TextView copyView = new TextView(this);
        copyView.setText("Copy");
        copyView.setTextColor(ContextCompat.getColor(this, R.color.primary));
        copyView.setTextSize(12);
        copyView.setTypeface(Typeface.DEFAULT_BOLD);
        copyView.setPadding(dp(10), dp(4), dp(10), dp(4));
        copyView.setOnClickListener(view -> copyToClipboard(code));

        header.addView(langView);
        header.addView(copyView);

        TextView codeText = new TextView(this);
        codeText.setText(code.trim());
        codeText.setTextColor(ContextCompat.getColor(this, R.color.code_text));
        codeText.setTextSize(13);
        codeText.setTypeface(Typeface.MONOSPACE);
        codeText.setLineSpacing(0, 1.05f);
        codeText.setTextIsSelectable(true);
        codeText.setHorizontallyScrolling(true);

        HorizontalScrollView horizontalScrollView = new HorizontalScrollView(this);
        horizontalScrollView.setFillViewport(false);
        horizontalScrollView.setHorizontalScrollBarEnabled(true);
        horizontalScrollView.addView(codeText);

        codeBox.addView(header);
        codeBox.addView(horizontalScrollView);
        return codeBox;
    }

    private List<MessageSegment> splitCodeBlocks(String raw) {
        List<MessageSegment> segments = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return segments;

        int cursor = 0;
        while (cursor < raw.length()) {
            int open = raw.indexOf("```", cursor);
            if (open < 0) {
                segments.add(new MessageSegment(false, "", raw.substring(cursor)));
                break;
            }
            if (open > cursor) {
                segments.add(new MessageSegment(false, "", raw.substring(cursor, open)));
            }

            int contentStart = open + 3;
            int close = raw.indexOf("```", contentStart);
            String block;
            if (close < 0) {
                block = raw.substring(contentStart);
                cursor = raw.length();
            } else {
                block = raw.substring(contentStart, close);
                cursor = close + 3;
            }

            String language = "";
            String code = block;
            String trimmedStart = block.replaceFirst("^\\s+", "");
            int firstNewLine = trimmedStart.indexOf('\n');
            if (firstNewLine > 0) {
                String possibleLanguage = trimmedStart.substring(0, firstNewLine).trim();
                if (possibleLanguage.matches("[A-Za-z0-9_+.#-]{1,24}")) {
                    language = possibleLanguage;
                    code = trimmedStart.substring(firstNewLine + 1);
                }
            }
            segments.add(new MessageSegment(true, language, code));
        }
        return segments;
    }

    private SpannableStringBuilder applyRichTextSpans(String text, InternetSearchClient.SearchResult searchResult) {
        SpannableStringBuilder spannable = replaceMarkdownLinksWithClickableText(text == null ? "" : text);
        applySourceCitationLinks(spannable, searchResult);
        applyInlineCodeSpans(spannable);
        return spannable;
    }

    private SpannableStringBuilder replaceMarkdownLinksWithClickableText(String raw) {
        String input = raw == null ? "" : raw;
        SpannableStringBuilder out = new SpannableStringBuilder();
        Pattern markdownLinkPattern = Pattern.compile(
                "\\[([^\\]\\n]{1,160})\\]\\((?:<([^>\\n]+)>|((?:https?://)[^\\s\\n]+))\\)"
        );
        Matcher matcher = markdownLinkPattern.matcher(input);
        int cursor = 0;
        while (matcher.find()) {
            out.append(input, cursor, matcher.start());
            int start = out.length();
            String label = matcher.group(1).trim();
            String url = firstNonEmpty(matcher.group(2), matcher.group(3)).trim();
            // Some old/plain Markdown links can include the final ')' in the captured URL.
            if (url.endsWith(")") && matcher.group(2) == null) {
                url = url.substring(0, url.length() - 1);
            }
            out.append(label.isEmpty() ? "open source" : label);
            int end = out.length();
            out.setSpan(new URLSpan(url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor = matcher.end();
        }
        out.append(input, cursor, input.length());
        return out;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }

    private void applySourceCitationLinks(SpannableStringBuilder spannable, InternetSearchClient.SearchResult searchResult) {
        if (searchResult == null) return;
        Map<String, String> sourceLinks = searchResult.sourceLinksByLabel();
        if (sourceLinks.isEmpty()) return;

        Matcher matcher = Pattern.compile("\\[(S\\d+|I\\d+)\\]").matcher(spannable.toString());
        while (matcher.find()) {
            String label = matcher.group(1);
            String url = sourceLinks.get(label);
            if (url == null || url.trim().isEmpty()) continue;
            spannable.setSpan(new URLSpan(url), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new StyleSpan(Typeface.BOLD), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.primary)), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void applyInlineCodeSpans(SpannableStringBuilder spannable) {
        Matcher matcher = Pattern.compile("`([^`\\n]+)`").matcher(spannable.toString());
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            spannable.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new BackgroundColorSpan(ContextCompat.getColor(this, R.color.inline_code_bg)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.code_text)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private boolean hasClickableLinks(SpannableStringBuilder spannable) {
        return spannable.getSpans(0, spannable.length(), URLSpan.class).length > 0;
    }

    private void copyToClipboard(String code) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("code", code == null ? "" : code.trim()));
            Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
        }
    }

    private ChatParts splitThinking(String raw) {
        if (raw == null) return new ChatParts("", "");
        StringBuilder thinking = new StringBuilder();
        Matcher matcher = Pattern.compile("(?is)<think>(.*?)</think>").matcher(raw);
        while (matcher.find()) {
            String chunk = matcher.group(1).trim();
            if (!chunk.isEmpty()) {
                if (thinking.length() > 0) thinking.append("\n\n");
                thinking.append(chunk);
            }
        }

        String answer = raw.replaceAll("(?is)<think>.*?</think>", "").trim();
        String lower = answer.toLowerCase(Locale.US);
        int openThink = lower.indexOf("<think>");
        if (openThink >= 0) {
            String before = answer.substring(0, openThink).trim();
            String after = answer.substring(openThink + "<think>".length()).trim();
            if (!after.isEmpty()) {
                if (thinking.length() > 0) thinking.append("\n\n");
                thinking.append(after);
            }
            answer = before;
        }
        answer = answer.replaceAll("(?i)</think>", "").trim();
        answer = answer.replaceFirst("(?is)^final answer:\\s*", "").trim();
        return new ChatParts(thinking.toString().trim(), answer);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        sendButton.setEnabled(!loading && !localModels.isEmpty());
        loadSelectedButton.setEnabled(!loading && !localModels.isEmpty());
        refreshLocalButton.setEnabled(!loading);
    }

    private boolean isInputTooLongError(Exception error) {
        String message = safeMessage(error).toLowerCase(Locale.US);
        return message.contains("input token") ||
                message.contains("too long") ||
                message.contains("maximum number of tokens") ||
                message.contains("max tokens") ||
                message.contains("exceed");
    }

    private String friendlyModelError(Exception error) {
        if (isInputTooLongError(error)) {
            return "The selected local model has a small context window. The app now compresses web context and retries automatically, but this question/context still exceeded the limit. Try a shorter question or disable Agentic internet search.";
        }
        return safeMessage(error);
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
