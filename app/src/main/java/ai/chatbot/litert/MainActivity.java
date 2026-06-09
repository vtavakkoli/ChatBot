package ai.chatbot.litert;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
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
    private TextView appVersionText;

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
        appVersionText = findViewById(R.id.appVersionText);
        appVersionText.setText("Version " + getAppVersionName());
        Button repositoryButton = findViewById(R.id.repositoryButton);
        Button chatButton = findViewById(R.id.chatButton);

        repositoryButton.setOnClickListener(view -> startActivity(new Intent(this, RepositoryActivity.class)));
        chatButton.setOnClickListener(view -> startActivity(new Intent(this, ChatActivity.class)));
    }

    private String getAppVersionName() {
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = getPackageManager().getPackageInfo(
                        getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)
                );
            } else {
                packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            }
            return packageInfo.versionName == null ? "1.0" : packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            return "1.0";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int localCount = LocalModelStore.listLocalModels(this).size();
        localStatusText.setText(localCount + " local chat model" + (localCount == 1 ? "" : "s") + " ready");
    }
}
