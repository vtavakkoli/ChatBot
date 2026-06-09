package com.example.chatbot;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class ModelRepositoryAdapter extends BaseAdapter {
    private final Context context;
    private final List<ModelInfo> models = new ArrayList<>();
    private ModelInfo selectedModel;

    public ModelRepositoryAdapter(Context context) {
        this.context = context;
    }

    public void setModels(List<ModelInfo> newModels, ModelInfo selectedModel) {
        models.clear();
        if (newModels != null) models.addAll(newModels);
        this.selectedModel = selectedModel;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return models.size();
    }

    @Override
    public ModelInfo getItem(int position) {
        return models.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_model_card, parent, false);
            holder = new ViewHolder();
            holder.cardRoot = convertView.findViewById(R.id.modelCardRoot);
            holder.icon = convertView.findViewById(R.id.modelIcon);
            holder.title = convertView.findViewById(R.id.modelTitle);
            holder.subtitle = convertView.findViewById(R.id.modelSubtitle);
            holder.badge = convertView.findViewById(R.id.modelBadge);
            holder.note = convertView.findViewById(R.id.modelNote);
            holder.status = convertView.findViewById(R.id.modelStatus);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        ModelInfo model = getItem(position);
        boolean selected = selectedModel != null && selectedModel.repoId.equals(model.repoId) && selectedModel.fileName.equals(model.fileName);
        holder.cardRoot.setBackgroundResource(selected ? R.drawable.bg_model_card_selected : R.drawable.bg_model_card);
        holder.icon.setImageResource(R.drawable.ic_model_chip);
        holder.title.setText(model.title());
        holder.title.setTypeface(Typeface.DEFAULT_BOLD);
        holder.subtitle.setText(model.subtitle());
        holder.badge.setText(model.badgeText());
        holder.note.setText(model.note == null || model.note.isEmpty() ? model.compatibilityText() : model.note);
        holder.status.setText(selected ? "Selected • tap Download model" : model.compatibilityText());
        return convertView;
    }

    private static class ViewHolder {
        View cardRoot;
        ImageView icon;
        TextView title;
        TextView subtitle;
        TextView badge;
        TextView note;
        TextView status;
    }
}
