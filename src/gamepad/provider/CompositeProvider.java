package gamepad.provider;

import java.util.ArrayList;
import java.util.List;

/**
 * Composite provider that chains multiple GamepadProviders.
 * Delegates to the first active/connected provider.
 * Allows seamless fallback (e.g. GLFW -> Linux /dev/input/js* on Linux,
 * or GLFW on Windows/macOS, or AndroidProvider on Android).
 */
public class CompositeProvider implements GamepadProvider {
    private final List<GamepadProvider> providers = new ArrayList<>();
    private GamepadProvider activeProvider = null;

    public void add(GamepadProvider p) {
        if (p != null) providers.add(p);
    }

    @Override
    public void update() {
        activeProvider = null;
        for (GamepadProvider p : providers) {
            try {
                p.update();
                if (p.isConnected()) {
                    activeProvider = p;
                    break;
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean isConnected() {
        return activeProvider != null && activeProvider.isConnected();
    }

    @Override
    public float getAxis(int index) {
        if (activeProvider != null) return activeProvider.getAxis(index);
        return (index == AXIS_L2 || index == AXIS_R2) ? -1f : 0f;
    }

    @Override
    public boolean getButton(int index) {
        if (activeProvider != null) return activeProvider.getButton(index);
        return false;
    }

    @Override
    public String getName() {
        if (activeProvider != null) return activeProvider.getName();
        return null;
    }

    @Override
    public void dispose() {
        for (GamepadProvider p : providers) {
            try {
                p.dispose();
            } catch (Exception ignored) {}
        }
        providers.clear();
        activeProvider = null;
    }
}
