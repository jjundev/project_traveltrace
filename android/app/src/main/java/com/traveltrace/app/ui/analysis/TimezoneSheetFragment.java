package com.traveltrace.app.ui.analysis;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.traveltrace.app.R;
import com.traveltrace.app.databinding.SheetTimezoneBinding;

/**
 * 타임존 확인 시트 (프로토타입 sheet:'tz'). 도시명은 제목·본문·확인 버튼 세 곳에 끼어들어가고,
 * 제목에서만 브랜드 색으로 강조된다.
 */
public class TimezoneSheetFragment extends BottomSheetDialogFragment {

    public static final String TAG = "tz_sheet";
    private static final String ARG_CITY = "city";

    /** 호스트 Fragment 가 구현한다. */
    public interface Listener {
        void onTimezoneConfirmed();

        void onChangeCityRequested();
    }

    private SheetTimezoneBinding binding;
    private Listener listener;

    public static TimezoneSheetFragment newInstance(String cityName) {
        TimezoneSheetFragment f = new TimezoneSheetFragment();
        Bundle args = new Bundle();
        args.putString(ARG_CITY, cityName);
        f.setArguments(args);
        return f;
    }

    /** 콘텐츠 렌더만 분리 — 다이얼로그 없이도 테스트할 수 있다. */
    public static void bindContent(SheetTimezoneBinding binding, String cityName) {
        Context ctx = binding.getRoot().getContext();

        String prefix = ctx.getString(R.string.tz_title_prefix);
        SpannableStringBuilder title = new SpannableStringBuilder();
        title.append(prefix).append(cityName).append(ctx.getString(R.string.tz_title_suffix));
        title.setSpan(new ForegroundColorSpan(ContextCompat.getColor(ctx, R.color.text_brand)),
                prefix.length(), prefix.length() + cityName.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        binding.tzTitle.setText(title);
        binding.tzBody.setText(ctx.getString(R.string.tz_body, cityName));
        binding.tzConfirm.setText(ctx.getString(R.string.tz_confirm, cityName));
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof Listener) {
            listener = (Listener) getParentFragment();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetTimezoneBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        String city = requireArguments().getString(ARG_CITY, "");
        bindContent(binding, city);

        binding.tzConfirm.setOnClickListener(v -> {
            if (listener != null) listener.onTimezoneConfirmed();
            dismiss();
        });
        binding.tzChangeCity.setOnClickListener(v -> {
            if (listener != null) listener.onChangeCityRequested();
            dismiss();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
