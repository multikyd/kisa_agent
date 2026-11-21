package com.kisapilot.kisaagent;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private final List<FileItem> fileList;
    private final OnItemClickListener listener;

    private OnItemLongClickListener longClickListener;

    // Interface for handling clicks
    public interface OnItemClickListener {
        void onItemClick(FileItem item);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(FileItem item);
    }

    public FileAdapter(List<FileItem> fileList, OnItemClickListener listener) {
        this.fileList = fileList;
        this.listener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener longClickListener) {
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Inflate the layout for each item
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        // Bind data to the views
        FileItem currentItem = fileList.get(position);
        holder.bind(currentItem, listener, longClickListener);
    }

    @Override
    public int getItemCount() {
        return fileList.size();
    }

    // ViewHolder class to hold the views for each item
    public class FileViewHolder extends RecyclerView.ViewHolder {
        public ImageView ivIcon;
        public TextView tvFileName;
        public TextView tvFileDetails;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.iv_file_icon);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvFileDetails = itemView.findViewById(R.id.tv_file_details);
        }

        public void bind(final FileItem item, final OnItemClickListener listener, OnItemLongClickListener longClickListener) {
            tvFileName.setText(item.getName());

            String detailsText;
            if (item.isDir()) {
                ivIcon.setImageResource(R.drawable.ic_folder); // Make sure you have this drawable
                String folderText = itemView.getContext().getString(R.string.file_explorer_folder);
                detailsText = folderText + "  |  " + item.getModified();
            } else {
                ivIcon.setImageResource(R.drawable.ic_file); // And this drawable
                String formattedSize = android.text.format.Formatter.formatShortFileSize(itemView.getContext(), item.getSize());
                detailsText = formattedSize + "  |  " + item.getModified();
            }

            tvFileDetails.setText(detailsText);
            itemView.setOnClickListener(v -> listener.onItemClick(item));

            // ★★★ [핵심 추가] 길게 누르기 리스너 설정 ★★★
            itemView.setOnLongClickListener(v -> {
                if (FileAdapter.this.longClickListener != null) {
                    // 롱클릭 이벤트가 발생하면, 인터페이스를 통해 Activity에 알립니다.
                    FileAdapter.this.longClickListener.onItemLongClick(item);
                    return true; // true를 반환해야 클릭 이벤트가 중복으로 발생하지 않습니다.
                }
                return false;
            });

        }
    }
}
