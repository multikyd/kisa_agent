package com.kisapilot.kisaagent;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout; // ★ FrameLayout import
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat; // ★ ContextCompat import
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    private final Context context;
    private final List<VideoItem> videoList;
    private final OnItemClickListener listener;
    private boolean isMultiSelectMode = false;

    public interface OnItemClickListener {
        void onItemClick(VideoItem item, int position);
        void onItemLongClick(VideoItem item, int position);
    }

    public VideoAdapter(Context context, List<VideoItem> videoList, OnItemClickListener listener) {
        this.context = context;
        this.videoList = videoList;
        this.listener = listener;
    }


    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_video, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        // 1. 현재 아이템을 가져옵니다.
        final VideoItem currentItem = videoList.get(position);

        // 2. 공통 UI 설정 (클릭 리스너, 선택 효과 등)
        holder.bind(currentItem, position); // bind 메서드는 이벤트 리스너와 선택 효과만 처리

        // 3. 아이템 타입에 따라 UI를 다르게 설정합니다.
        if (currentItem.getType() == VideoItem.TYPE_DRIVE_LOG) {
            // --- 드라이브 로그(Drive Log) 아이템일 경우 ---
            holder.tvVideoTitle.setText(currentItem.getRouteName());
            holder.tvRouteName.setText(currentItem.getDisplayTitle());
            holder.tvSegmentCount.setText("Segments: " + currentItem.getSegmentCount());
            holder.tvVideoSize.setVisibility(View.GONE);
        } else if (currentItem.getType() == VideoItem.TYPE_SCREEN_REC) {
            // --- 스크린 녹화(ScreenRecs) 아이템일 경우 ---
            holder.tvVideoTitle.setText(currentItem.getDisplayTitle());

            // ScreenRecs는 Route Name과 Segment Count가 필요 없으므로 숨깁니다.
            holder.tvRouteName.setVisibility(View.GONE);
            holder.tvSegmentCount.setVisibility(View.GONE);
            holder.tvVideoSize.setVisibility(View.VISIBLE);
            if (context instanceof ScreenRecsActivity) {
                ScreenRecsActivity activity = (ScreenRecsActivity) context;
                String formattedSize = activity.getFormattedSize(currentItem.getSize());
                holder.tvVideoSize.setText(formattedSize);
            }
        }
    }

    @Override
    public int getItemCount() {
        return videoList.size();
    }

    class VideoViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumbnail;
        TextView tvVideoTitle, tvRouteName, tvSegmentCount, tvVideoSize;
        View overlay;
        FrameLayout cardBorderLayout; // ★ 테두리를 위한 FrameLayout

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            ivThumbnail = itemView.findViewById(R.id.iv_thumbnail);
            tvVideoTitle = itemView.findViewById(R.id.tv_video_title);
            tvVideoSize = itemView.findViewById(R.id.tv_video_size);
            tvRouteName = itemView.findViewById(R.id.tv_route_name);
            tvSegmentCount = itemView.findViewById(R.id.tv_segment_count);
            overlay = itemView.findViewById(R.id.overlay);
            cardBorderLayout = itemView.findViewById(R.id.card_border_layout); // ★ 연결
        }

        void bind(final VideoItem item, final int position) {
            // ... (기존의 텍스트 설정 로직은 동일) ...
            tvVideoTitle.setText(item.getDisplayTitle());
            if (item.getType() == VideoItem.TYPE_DRIVE_LOG) {
                tvRouteName.setVisibility(View.VISIBLE);
                tvSegmentCount.setVisibility(View.VISIBLE);
                tvRouteName.setText(item.getRouteName());
                tvSegmentCount.setText("Segments: " + item.getSegmentCount());
            } else {
                tvRouteName.setVisibility(View.GONE);
                tvSegmentCount.setVisibility(View.GONE);
            }

            Glide.with(context)
                    .load(item.getThumbnailUrl())
                    .placeholder(R.drawable.ic_launcher_background)
                    .error(R.drawable.ic_file)
                    .into(ivThumbnail);

            // ★★★ [핵심] 선택 상태에 따라 테두리와 오버레이 UI를 모두 변경 ★★★
            if (item.isSelected()) {
                // 1. 선택된 상태: 파란색 테두리 + 반투명 파란색 오버레이 표시
                cardBorderLayout.setBackground(ContextCompat.getDrawable(context, R.drawable.card_border_selected));
                overlay.setVisibility(View.VISIBLE);
            } else {
                // 2. 선택되지 않은 상태: 투명 테두리 + 오버레이 숨김
                cardBorderLayout.setBackground(ContextCompat.getDrawable(context, R.drawable.card_border_normal));
                overlay.setVisibility(View.GONE);
            }

            // --- 클릭/롱클릭 이벤트 리스너는 기존과 동일 ---
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item, position);
                }
            });
            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onItemLongClick(item, position);
                }
                return true;
            });
        }
    }

    // --- 다중 선택 관련 헬퍼 메서드들 ---
    public void setMultiSelectMode(boolean mode) {
        isMultiSelectMode = mode;
        if (!mode) {
            for (VideoItem item : videoList) {
                item.setSelected(false);
            }
        }
        notifyDataSetChanged();
    }

    public boolean isMultiSelectMode() {
        return isMultiSelectMode;
    }

    public void toggleSelection(int position) {
        videoList.get(position).setSelected(!videoList.get(position).isSelected());
        notifyItemChanged(position);
    }

    public List<VideoItem> getSelectedItems() {
        List<VideoItem> selectedItems = new ArrayList<>();
        for (VideoItem item : videoList) {
            if (item.isSelected()) {
                selectedItems.add(item);
            }
        }
        return selectedItems;
    }

    // ★★★ [핵심] 전체 선택/해제 토글 기능으로 수정 ★★★
    public void selectAll() {
        // 현재 모든 아이템이 선택되었는지 확인
        boolean allSelected = getSelectedItems().size() == videoList.size();
        // 모든 아이템의 선택 상태를 반대로 설정 (전체 선택 -> 전체 해제, 일부 선택 -> 전체 선택)
        for (VideoItem item : videoList) {
            item.setSelected(!allSelected);
        }
        notifyDataSetChanged();
    }
    // ---------------------------------
}
