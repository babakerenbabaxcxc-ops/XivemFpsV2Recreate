package x1vemfps.modid.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * X1VEMFPS v2: V1-sized compact HUD with Android-friendly metrics and a small settings screen.
 * Default HUD dimensions intentionally stay close to the original V1 footprint.
 */
public final class X1vemFPSClient implements ClientModInitializer {
    private static final int DEFAULT_X = 8;
    private static final int DEFAULT_Y = 8;
    private static final int HEIGHT = 20;
    private static final int MIN_WIDTH = 72;
    private static final int GAP = 1;

    private static int hudX = DEFAULT_X;
    private static int hudY = DEFAULT_Y;
    private static int borderThickness = 1;
    private static boolean showFps = true;
    private static boolean showCpu = true;
    private static boolean showGpu = true;
    private static boolean showGpuTemp = false;
    private static boolean showPing = false;
    private static long updateIntervalMs = 500;

    private static long lastSampleNs;
    private static long lastTotalCpu;
    private static long lastIdleCpu;
    private static double cpuLoad = Double.NaN;
    private static String gpuLoad = "N/A";
    private static String gpuTemp = "N/A";
    private static long ping = -1;
    private static KeyMapping settingsKey;
    private static Path configFile;
    private static boolean moveMode;
    private static boolean draggingHud;
    private static double dragDx;
    private static double dragDy;

    @Override
    public void onInitializeClient() {
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.x1vemfps.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                "category.x1vemfps"
        ));
        configFile = Minecraft.getInstance().gameDirectory.toPath().resolve("config/x1vemfps-v2.properties");
        loadConfig();

        HudRenderCallback.EVENT.register((graphics, delta) -> renderHud(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (settingsKey.consumeClick()) {
                if (mc.screen == null) mc.setScreen(new SettingsScreen());
                else if (mc.screen instanceof SettingsScreen) mc.setScreen(null);
            }
            sampleMetrics(mc);
            pollHudDrag(mc);
        });
    }

    private static void renderHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;

        String[] labels = {
                showFps ? "FPS" : null,
                showCpu ? "CPU" : null,
                showGpu ? "GPU" : null,
                showGpuTemp ? "TEMP" : null,
                showPing ? "PING" : null
        };
        String[] values = {
                showFps ? Integer.toString(mc.getFps()) : null,
                showCpu ? formatCpu() : null,
                showGpu ? gpuLoad : null,
                showGpuTemp ? gpuTemp : null,
                showPing ? (ping >= 0 ? ping + "ms" : "--") : null
        };

        int x = hudX;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            int width = panelWidth(labels[i], values[i]);
            drawPanel(g, x, hudY, width, labels[i], values[i]);
            x += width + GAP;
        }

        if (moveMode) {
            int totalWidth = Math.max(1, x - hudX - GAP);
            g.fill(hudX, hudY - 2, hudX + totalWidth, hudY - 1, 0xFFFFFFFF);
        }
    }

    private static int panelWidth(String label, String value) {
        Minecraft mc = Minecraft.getInstance();
        int text = mc.font.width(label) + mc.font.width(value) + 18;
        return Math.max(MIN_WIDTH, text);
    }

    private static void drawPanel(GuiGraphics g, int x, int y, int width, String label, String value) {
        int fill = 0x9A14181D; // small translucent fill, same compact footprint as V1
        int border = 0xD0AAB5C0;
        int divider = 0x6677818D;
        int labelColor = 0xFF9EA8B3;
        int valueColor = 0xFFE8EDF2;

        g.fill(x, y, x + width, y + HEIGHT, fill);
        for (int i = 0; i < borderThickness; i++) {
            g.fill(x + i, y + i, x + width - i, y + i + 1, border);
            g.fill(x + i, y + HEIGHT - i - 1, x + width - i, y + HEIGHT - i, border);
            g.fill(x + i, y + i, x + i + 1, y + HEIGHT - i, border);
            g.fill(x + width - i - 1, y + i, x + width - i, y + HEIGHT - i, border);
        }

        int dividerX = x + Math.max(28, mcFontWidth(label) + 17);
        g.fill(dividerX, y + 4, dividerX + 1, y + HEIGHT - 4, divider);
        g.drawString(Minecraft.getInstance().font, Component.literal(label), x + 6, y + 6, labelColor, false);
        int valueWidth = mcFontWidth(value);
        g.drawString(Minecraft.getInstance().font, Component.literal(value), x + width - 6 - valueWidth, y + 6, valueColor, false);
    }

    private static int mcFontWidth(String s) {
        return Minecraft.getInstance().font.width(s);
    }

    private static void sampleMetrics(Minecraft mc) {
        long now = System.nanoTime();
        if (now - lastSampleNs < updateIntervalMs * 1_000_000L) return;
        lastSampleNs = now;

        long[] cpu = readProcStat();
        if (cpu != null) {
            long total = cpu[0];
            long idle = cpu[1];
            if (lastTotalCpu != 0 && total > lastTotalCpu) {
                long totalDelta = total - lastTotalCpu;
                long idleDelta = idle - lastIdleCpu;
                cpuLoad = Math.max(0, Math.min(100, 100.0 * (totalDelta - idleDelta) / totalDelta));
            }
            lastTotalCpu = total;
            lastIdleCpu = idle;
        }

        gpuLoad = readFirst("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/gpu_busy", "N/A");
        if (!gpuLoad.equals("N/A") && !gpuLoad.endsWith("%")) gpuLoad += "%";
        gpuTemp = readGpuTemp();
        ping = readPing(mc);
    }

    private static String formatCpu() {
        return Double.isNaN(cpuLoad) ? "--%" : String.format(Locale.ROOT, "%.0f%%", cpuLoad);
    }

    private static long[] readProcStat() {
        try {
            String line = Files.readAllLines(Path.of("/proc/stat")).get(0);
            String[] p = line.trim().split("\\s+");
            if (p.length < 5 || !p[0].equals("cpu")) return null;
            long user = Long.parseLong(p[1]);
            long nice = Long.parseLong(p[2]);
            long system = Long.parseLong(p[3]);
            long idle = Long.parseLong(p[4]);
            long iowait = p.length > 5 ? Long.parseLong(p[5]) : 0;
            long irq = p.length > 6 ? Long.parseLong(p[6]) : 0;
            long softirq = p.length > 7 ? Long.parseLong(p[7]) : 0;
            long steal = p.length > 8 ? Long.parseLong(p[8]) : 0;
            long total = user + nice + system + idle + iowait + irq + softirq + steal;
            return new long[]{total, idle + iowait};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readFirst(String... paths) {
        for (String p : paths) {
            try {
                String s = Files.readString(Path.of(p)).trim();
                if (!s.isEmpty()) return s;
            } catch (Throwable ignored) {}
        }
        return "N/A";
    }

    private static String readGpuTemp() {
        try {
            for (int i = 0; i < 40; i++) {
                Path type = Path.of("/sys/class/thermal/thermal_zone" + i + "/type");
                Path temp = Path.of("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (!Files.exists(type) || !Files.exists(temp)) continue;
                String t = Files.readString(type).trim().toLowerCase(Locale.ROOT);
                if (t.contains("gpu") || t.contains("gpufreq")) {
                    long v = Long.parseLong(Files.readString(temp).trim());
                    if (v > 1000) v /= 1000;
                    return v + "C";
                }
            }
        } catch (Throwable ignored) {}
        return "N/A";
    }

    private static long readPing(Minecraft mc) {
        try {
            if (mc.player == null || mc.getConnection() == null) return -1;
            Object info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info == null) return -1;
            Object result = info.getClass().getMethod("getLatency").invoke(info);
            return result instanceof Number n ? n.longValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void pollHudDrag(Minecraft mc) {
        if (!moveMode || mc.screen != null) return;
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1];
        double[] my = new double[1];
        org.lwjgl.glfw.GLFW.glfwGetCursorPos(window, mx, my);
        double sx = mc.getWindow().getGuiScaledWidth() / (double) mc.getWindow().getWidth();
        double sy = mc.getWindow().getGuiScaledHeight() / (double) mc.getWindow().getHeight();
        double x = mx[0] * sx;
        double y = my[0] * sy;
        boolean left = org.lwjgl.glfw.GLFW.glfwGetMouseButton(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        int totalWidth = hudWidth(mc);
        boolean inside = x >= hudX && x <= hudX + totalWidth && y >= hudY && y <= hudY + HEIGHT;
        if (left && !draggingHud && inside) {
            draggingHud = true;
            dragDx = x - hudX;
            dragDy = y - hudY;
        } else if (!left) {
            if (draggingHud) saveConfig();
            draggingHud = false;
        }
        if (draggingHud) {
            hudX = Math.max(0, (int) (x - dragDx));
            hudY = Math.max(0, (int) (y - dragDy));
            hudX = Math.min(hudX, Math.max(0, mc.getWindow().getGuiScaledWidth() - totalWidth));
            hudY = Math.min(hudY, Math.max(0, mc.getWindow().getGuiScaledHeight() - HEIGHT));
        }
    }

    private static int hudWidth(Minecraft mc) {
        int total = 0;
        String[] labels = { showFps ? "FPS" : null, showCpu ? "CPU" : null, showGpu ? "GPU" : null, showGpuTemp ? "TEMP" : null, showPing ? "PING" : null };
        String[] values = { showFps ? Integer.toString(mc.getFps()) : null, showCpu ? formatCpu() : null, showGpu ? gpuLoad : null, showGpuTemp ? gpuTemp : null, showPing ? (ping >= 0 ? ping + "ms" : "--") : null };
        for (int i = 0; i < labels.length; i++) if (labels[i] != null) total += panelWidth(labels[i], values[i]) + GAP;
        return Math.max(1, total - GAP);
    }

    private static final class SettingsScreen extends Screen {
        SettingsScreen() {
            super(Component.literal("X1VEMFPS v2 Settings"));
        }

        @Override
        protected void init() {
            int cx = width / 2;
            int top = Math.max(20, height / 2 - 90);
            int w = 180;
            int x = cx - w / 2;
            int row = 22;

            addRenderableWidget(button(x, top, w, row, "FPS: " + on(showFps), b -> { showFps = !showFps; saveConfig(); rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, "CPU: " + on(showCpu), b -> { showCpu = !showCpu; saveConfig(); rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, "GPU: " + on(showGpu), b -> { showGpu = !showGpu; saveConfig(); rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, "GPU Temp: " + on(showGpuTemp), b -> { showGpuTemp = !showGpuTemp; saveConfig(); rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, "Ping: " + on(showPing), b -> { showPing = !showPing; saveConfig(); rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, "Border: " + borderThickness + "px", b -> { borderThickness = borderThickness >= 4 ? 1 : borderThickness + 1; saveConfig(); rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, "Update: " + updateIntervalMs + "ms", b -> { updateIntervalMs = updateIntervalMs >= 1000 ? 250 : updateIntervalMs + 250; saveConfig(); rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, moveMode ? "MOVE HUD: ON" : "MOVE HUD: OFF", b -> { moveMode = !moveMode; rebuild(); }));
            addRenderableWidget(button(x, top += row, w, row, "RESET POSITION", b -> { hudX = DEFAULT_X; hudY = DEFAULT_Y; saveConfig(); }));
            addRenderableWidget(button(x, top += row, w, row, "Done", b -> minecraft.setScreen(null)));
        }

        private void rebuild() { clearWidgets(); init(); }

        private net.minecraft.client.gui.components.Button button(int x, int y, int w, int h, String text, net.minecraft.client.gui.components.Button.OnPress press) {
            return net.minecraft.client.gui.components.Button.builder(Component.literal(text), press).bounds(x, y, w, h).build();
        }

        private String on(boolean b) { return b ? "ON" : "OFF"; }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            renderBackground(g, mouseX, mouseY, delta);
            super.render(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
            g.drawString(font, Component.literal("X closes • MOVE HUD, then Done and drag the HUD"), 8, height - 14, 0xFFB8C0C8);
        }
    }

    private static void loadConfig() {
        try {
            if (!Files.exists(configFile)) return;
            for (String line : Files.readAllLines(configFile)) {
                if (line.startsWith("x=")) hudX = Integer.parseInt(line.substring(2));
                else if (line.startsWith("y=")) hudY = Integer.parseInt(line.substring(2));
                else if (line.startsWith("border=")) borderThickness = Integer.parseInt(line.substring(7));
                else if (line.startsWith("fps=")) showFps = Boolean.parseBoolean(line.substring(4));
                else if (line.startsWith("cpu=")) showCpu = Boolean.parseBoolean(line.substring(4));
                else if (line.startsWith("gpu=")) showGpu = Boolean.parseBoolean(line.substring(4));
                else if (line.startsWith("temp=")) showGpuTemp = Boolean.parseBoolean(line.substring(5));
                else if (line.startsWith("ping=")) showPing = Boolean.parseBoolean(line.substring(5));
                else if (line.startsWith("interval=")) updateIntervalMs = Long.parseLong(line.substring(9));
            }
            borderThickness = Math.max(1, Math.min(4, borderThickness));
            updateIntervalMs = Math.max(250, Math.min(1000, updateIntervalMs));
        } catch (Throwable ignored) {}
    }

    private static void saveConfig() {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile,
                    "x=" + hudX + "\n" +
                    "y=" + hudY + "\n" +
                    "border=" + borderThickness + "\n" +
                    "fps=" + showFps + "\n" +
                    "cpu=" + showCpu + "\n" +
                    "gpu=" + showGpu + "\n" +
                    "temp=" + showGpuTemp + "\n" +
                    "ping=" + showPing + "\n" +
                    "interval=" + updateIntervalMs + "\n");
        } catch (IOException ignored) {}
    }
}
