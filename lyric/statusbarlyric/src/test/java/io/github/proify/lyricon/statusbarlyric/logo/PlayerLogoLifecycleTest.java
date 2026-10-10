package io.github.proify.lyricon.statusbarlyric.logo;

import org.junit.Test;
import java.lang.reflect.Field;
import static org.mockito.Mockito.*;
import static org.mockito.Answers.CALLS_REAL_METHODS;

public class PlayerLogoLifecycleTest {
    private SuperLogo viewWith(AppLogoStrategy strategy) throws Exception {
        SuperLogo view = mock(SuperLogo.class, CALLS_REAL_METHODS);
        Field field = SuperLogo.class.getDeclaredField("strategy");
        field.setAccessible(true);
        field.set(view, strategy);
        return view;
    }
    @Test public void switchRefreshesIconEvenWhenBothProviderLogosAreNull() throws Exception {
        AppLogoStrategy strategy = mock(AppLogoStrategy.class);
        SuperLogo view = viewWith(strategy);
        view.setActivePackage("com.netease.cloudmusic");
        view.setActivePackage("com.tencent.qqmusic");
        verify(strategy, times(2)).updateContent();
    }
    @Test public void duplicatePackageDoesNotReloadIcon() throws Exception {
        AppLogoStrategy strategy = mock(AppLogoStrategy.class);
        SuperLogo view = viewWith(strategy);
        view.setActivePackage("com.tencent.qqmusic");
        view.setActivePackage("com.tencent.qqmusic");
        verify(strategy).updateContent();
    }
    @Test public void appStrategyRechecksOnAttach() {
        SuperLogo view = mock(SuperLogo.class);
        when(view.getDrawable()).thenReturn(mock(android.graphics.drawable.Drawable.class));
        AppLogoStrategy strategy = spy(new AppLogoStrategy(view));
        doNothing().when(strategy).updateContent();
        strategy.onAttach();
        verify(strategy).updateContent();
    }
    @Test public void providerStrategyRetriesAfterPreviouslyFailedLoad() {
        ProviderStrategy strategy = spy(new ProviderStrategy(mock(SuperLogo.class)));
        doNothing().when(strategy).updateContent();
        strategy.onAttach();
        verify(strategy).updateContent();
    }
}
