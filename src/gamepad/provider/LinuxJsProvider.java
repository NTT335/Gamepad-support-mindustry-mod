package gamepad.provider;

import java.io.*;
import arc.util.Log;

/**
 * GamepadProvider implementation that reads Linux joystick devices from /dev/input/js*.
 * Runs a daemon background thread that continuously reads 8-byte joystick events.
 */
public class LinuxJsProvider implements GamepadProvider {
    private volatile boolean connected;
    private volatile float[] axes = new float[8];
    private volatile boolean[] buttons = new boolean[32];
    private String name;
    private Thread readerThread;
    private volatile boolean running = true;

    private boolean permissionWarningLogged;

    public LinuxJsProvider() {
        // Initialize triggers at rest position
        axes[2] = -1f;
        axes[5] = -1f;

        String os = System.getProperty("os.name");
        if (os == null || !os.toLowerCase().contains("linux")) {
            running = false;
            return;
        }

        readerThread = new Thread(this::readerLoop, "GamepadReader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private File findDevice() {
        for (int i = 0; i <= 7; i++) {
            File f = new File("/dev/input/js" + i);
            if (f.exists()) {
                if (f.canRead()) {
                    return f;
                } else if (!permissionWarningLogged) {
                    permissionWarningLogged = true;
                    Log.warn("Gamepad: cannot read @. Add user to input group (sudo usermod -aG input $USER) and relogin", f.getPath());
                }
            }
        }
        return null;
    }

    private void resetState() {
        for (int i = 0; i < buttons.length; i++) {
            buttons[i] = false;
        }
        for (int i = 0; i < axes.length; i++) {
            axes[i] = 0f;
        }
        axes[2] = -1f;
        axes[5] = -1f;
    }

    private void readerLoop() {
        byte[] buf = new byte[8];

        while (running) {
            File device = findDevice();
            if (device == null) {
                connected = false;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            FileInputStream fis = null;
            try {
                fis = new FileInputStream(device);
                name = device.getPath();
                connected = true;
                Log.info("Gamepad: connected to @", name);

                while (running) {
                    int bytesRead = 0;
                    while (bytesRead < 8) {
                        int r = fis.read(buf, bytesRead, 8 - bytesRead);
                        if (r == -1) {
                            throw new EOFException("Device disconnected");
                        }
                        bytesRead += r;
                    }

                    // Parse event: 4 bytes timestamp (ignored), 2 bytes value (signed short), 1 byte type, 1 byte number
                    int value = (buf[5] << 8) | (buf[4] & 0xFF); // signed short
                    int type = buf[6] & 0x7F;
                    int number = buf[7] & 0xFF;

                    if (type == 1 && number < 32) {
                        buttons[number] = (value == 1);
                    } else if (type == 2 && number < 8) {
                        axes[number] = value / 32767.0f;
                    }
                }
            } catch (Exception e) {
                if (running) {
                    Log.err("Gamepad: error reading @: @", name, e.getMessage());
                }
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (IOException ignored) {
                    }
                }
                connected = false;
                resetState();
            }

            if (running) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    @Override
    public void update() {
        // No-op: the reader thread does the work
    }

    @Override
    public float getAxis(int index) {
        if (index >= 0 && index < axes.length) {
            return axes[index];
        }
        return 0f;
    }

    @Override
    public boolean getButton(int index) {
        if (index >= 0 && index < buttons.length) {
            return buttons[index];
        }
        return false;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void dispose() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }
}
