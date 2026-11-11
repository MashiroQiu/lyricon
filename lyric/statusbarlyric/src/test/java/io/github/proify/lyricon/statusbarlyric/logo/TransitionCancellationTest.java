package io.github.proify.lyricon.statusbarlyric.logo;

import android.animation.Animator;
import android.view.View;
import com.daimajia.androidanimations.library.BaseViewAnimator;
import com.daimajia.androidanimations.library.YoYo;
import io.github.proify.lyricon.lyric.view.yoyo.AnimConfig;
import io.github.proify.lyricon.lyric.view.yoyo.YoYoAnimation;
import kotlin.Unit;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TransitionCancellationTest {
    private static class Stage {
        final YoYo.AnimationComposer composer = mock(YoYo.AnimationComposer.class, RETURNS_SELF);
        final YoYo.YoYoString handle = mock(YoYo.YoYoString.class);
        Animator.AnimatorListener listener;
        Stage(View view) {
            when(composer.withListener(any())).thenAnswer(call -> {
                listener = call.getArgument(0);
                return composer;
            });
            when(composer.playOn(view)).thenReturn(handle);
        }
    }

    private static class Harness implements AutoCloseable {
        final View view = mock(View.class);
        final AnimConfig config = mock(AnimConfig.class);
        final BaseViewAnimator technique = mock(BaseViewAnimator.class);
        final List<Stage> stages = new ArrayList<>();
        final MockedStatic<YoYo> yoyo = mockStatic(YoYo.class);
        boolean finishFirstImmediately;
        Harness() {
            Map<Integer, Object> tags = new HashMap<>();
            doAnswer(call -> {
                tags.put(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(view).setTag(anyInt(), any());
            when(view.getTag(anyInt())).thenAnswer(call -> tags.get(call.getArgument(0)));
            when(config.getTechnique()).thenReturn(technique);
            yoyo.when(() -> YoYo.with(technique)).thenAnswer(call -> {
                Stage stage = new Stage(view);
                stages.add(stage);
                if (finishFirstImmediately && stages.size() == 1) {
                    when(stage.composer.playOn(view)).thenAnswer(play -> {
                        stage.listener.onAnimationEnd(mock(Animator.class));
                        return stage.handle;
                    });
                }
                return stage.composer;
            });
        }
        void start(Runnable action) {
            YoYoAnimation.INSTANCE.switchContent(view, config, config, target -> {
                action.run(); return Unit.INSTANCE;
            });
        }
        public void close() { yoyo.close(); }
    }

    @Test public void cancellingCannotRunTheOldContentChangeOrStartAnIncomingAnimation() {
        try (Harness h = new Harness()) {
            int[] calls = {0};
            h.start(() -> calls[0]++);
            Stage old = h.stages.get(0);
            doAnswer(call -> {
                old.listener.onAnimationEnd(mock(Animator.class)); return null;
            }).when(old.handle).stop(true);
            YoYoAnimation.INSTANCE.cancelAnimation(h.view);
            assertEquals(0, calls[0]);
            assertEquals(1, h.stages.size());
        }
    }

    @Test public void lateEndFromAnOldIncomingAnimationCannotCancelTheReplacement() {
        try (Harness h = new Harness()) {
            h.start(() -> {});
            h.stages.get(0).listener.onAnimationEnd(mock(Animator.class));
            Stage incoming = h.stages.get(1);
            int[] calls = {0};
            h.start(() -> calls[0]++);
            Stage replacement = h.stages.get(2);
            incoming.listener.onAnimationEnd(mock(Animator.class));
            replacement.listener.onAnimationEnd(mock(Animator.class));
            assertEquals(1, calls[0]);
            assertEquals(4, h.stages.size());
        }
    }

    @Test public void immediateOutgoingCompletionKeepsTheIncomingCancellationHandle() {
        try (Harness h = new Harness()) {
            h.finishFirstImmediately = true;
            h.start(() -> {});
            assertEquals(2, h.stages.size());
            YoYoAnimation.INSTANCE.cancelAnimation(h.view);
            verify(h.stages.get(1).handle).stop(true);
            verify(h.stages.get(0).handle, never()).stop(true);
        }
    }
}
