package gamepad.provider;

/**
 * Abstract interface for reading gamepad state from platform-specific APIs.
 * Implementations must fill the provided arrays with normalized values.
 */
public interface GamepadProvider {
    /** Update the provider state. Called from appropriate thread. */
    void update();

    /** Whether a controller is currently connected. */
    boolean isConnected();

    /** Get axis value. Index 0-7. Range: -1..1 (triggers: -1=released, 1=full). */
    float getAxis(int index);

    /** Get button state. Index 0-19. */
    boolean getButton(int index);

    /** Get the controller name, or null. */
    String getName();

    /** Release resources. */
    void dispose();

    // Axis indices (matching Linux js* layout)
    int AXIS_LX = 0, AXIS_LY = 1, AXIS_L2 = 2;
    int AXIS_RX = 3, AXIS_RY = 4, AXIS_R2 = 5;
    int AXIS_DPAD_X = 6, AXIS_DPAD_Y = 7;
}
