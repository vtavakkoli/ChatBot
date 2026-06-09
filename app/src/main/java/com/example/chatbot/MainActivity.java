package com.example.chatbot;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private TextView localStatusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        localStatusText = findViewById(R.id.localStatusText);
        Button repositoryButton = findViewById(R.id.repositoryButton);
        Button chatButton = findViewById(R.id.chatButton);

        repositoryButton.setOnClickListener(view -> startActivity(new Intent(this, RepositoryActivity.class)));
        chatButton.setOnClickListener(view -> startActivity(new Intent(this, ChatActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        int localCount = LocalModelStore.listLocalModels(this).size();
        localStatusText.setText(localCount + " local chat model" + (localCount == 1 ? "" : "s") + " ready");
    }
}
