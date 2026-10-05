package notoshi.easyplacelightfix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Tiny dependency-free configuration, read from
 * {@code <gameDir>/config/easyplace_lightfix.properties}.
 *
 * <p>Created on first launch with the default values, so the file is always there to be edited.</p>
 */
public final class ModConfig
{
    private static final String FILE_NAME = "easyplace_lightfix.properties";

    /** Master switch for all diagnostics. */
    public boolean debug = true;

    /** Also append everything to {@code <gameDir>/logs/easyplace_lightfix.log}. */
    public boolean logToFile = true;

    /**
     * Minimum milliseconds between two identical {@code [TRACE]} lines. A light block is queried by
     * the schematic ray trace every single tick while you look at it, so without throttling this
     * would flood the log.
     */
    public long traceThrottleMs = 1500L;

    /**
     * Minimum milliseconds between two {@code [BLOCKED]} snapshots. Every blocked click would
     * otherwise dump ~15 lines while you hold the right mouse button.
     */
    public long snapshotThrottleMs = 1500L;

    /**
     * Number of blocked events that are always dumped in full, regardless of the throttle, so the
     * very first failures are never swallowed.
     */
    public int unthrottledSnapshots = 3;

    /**
     * Send Litematica Printer's server-side rotation when the Easy Place target is a
     * {@code minecraft:light}.
     *
     * <p>Only affects light blocks; every other block keeps Litematica's stock behaviour and sends no
     * extra packets. The rotation is sent as a packet <b>only</b> — the local player's yaw and pitch
     * are never touched, so the camera does not move.</p>
     *
     * <p>This is visible to the server and to any anti-cheat that tracks look direction: the server
     * briefly sees the player aiming at the block being placed. Turn this off if that matters more
     * than placement succeeding.</p>
     */
    public boolean rotateForLightBlocks = true;

    /**
     * Recover the Easy Place target when Litematica discards it because a closer vanilla block won
     * the ray trace.
     *
     * <p>Only ever substitutes a target that is a {@code minecraft:light} in the loaded schematic,
     * found by Litematica's own schematic-only trace at Litematica's own block range. Without it,
     * Easy Place returns {@code FAIL} before it ever builds a click.</p>
     *
     * <p>Applies to the rewritten path only ({@code easyPlacePostRewrite = true}); the legacy path has
     * no equivalent step.</p>
     */
    public boolean rescueLightTarget = true;

    /**
     * Click a light block's target cell directly instead of using the click position Litematica
     * computed.
     *
     * <p><b>This is the switch that makes placement work on the rewritten path.</b> With Easy Place's
     * {@code easyPlaceClickAdjacent} on (the default), Litematica has to click an existing block,
     * because vanilla cannot click air. A light block in mid-air has no solid neighbour, the search
     * returns nothing, and Easy Place reports {@code FAIL} with a valid target and a valid item.</p>
     *
     * <p><b>Independent of {@code easyPlaceClickAdjacent}.</b> The hook is on
     * {@code getClickPosition}, the single point both of Litematica's branches pass through, so the
     * mod behaves identically with that option on or off. Hooking
     * {@code getAdjacentClickPosition} instead would be a silent no-op with the option off, since that
     * method only runs on the {@code on} branch.</p>
     *
     * <p>Only ever applied when the target is a {@code minecraft:light} in the loaded schematic
     * <b>and</b> the cell is still empty in the client world. Every other block, and every light
     * cell that is actually occupied, keeps Litematica's stock behaviour.</p>
     */
    public boolean directClickForLightBlocks = true; // always on - needed for light blocks in air

    private static ModConfig instance;

    public static ModConfig get()
    {
        if (instance == null)
        {
            instance = new ModConfig().load();
        }

        return instance;
    }

    public static Path configPath()
    {
        return Paths.get("config", FILE_NAME);
    }

    private ModConfig load()
    {
        Path path = configPath();

        if (Files.exists(path))
        {
            Properties props = new Properties();

            try (InputStream in = Files.newInputStream(path))
            {
                props.load(in);
                this.debug = bool(props, "debug", this.debug);
                this.logToFile = bool(props, "logToFile", this.logToFile);
                this.traceThrottleMs = num(props, "traceThrottleMs", this.traceThrottleMs);
                this.snapshotThrottleMs = num(props, "snapshotThrottleMs", this.snapshotThrottleMs);
                this.unthrottledSnapshots = (int) num(props, "unthrottledSnapshots", this.unthrottledSnapshots);
                this.rotateForLightBlocks = bool(props, "rotateForLightBlocks", this.rotateForLightBlocks);
                this.rescueLightTarget = bool(props, "rescueLightTarget", this.rescueLightTarget);
                this.directClickForLightBlocks = bool(props, "directClickForLightBlocks", this.directClickForLightBlocks);
            }
            catch (IOException | IllegalArgumentException e)
            {
                System.err.println("[EasyPlaceLightFix] Could not read " + path + ", using defaults: " + e);
            }
        }
        else
        {
            try
            {
                org.slf4j.LoggerFactory.getLogger(EasyPlaceLightFixClient.MOD_ID + "-config")
                        .info("Config not found, creating default config at {}", path);
            }
            catch (Throwable ignored) {}
            writeDefault(path);
        }

        return this;
    }

    private static boolean bool(Properties props, String key, boolean def)
    {
        String raw = props.getProperty(key);

        if (raw == null)
        {
            return def;
        }

        return Boolean.parseBoolean(raw.trim());
    }

    private static long num(Properties props, String key, long def)
    {
        String raw = props.getProperty(key);

        if (raw == null)
        {
            return def;
        }

        try
        {
            return Long.parseLong(raw.trim());
        }
        catch (NumberFormatException e)
        {
            return def;
        }
    }

    private void writeDefault(Path path)
    {
        String text = ""
                + "# Easy Place Light Fix - settings\n"
                + "#\n"
                + "# debug               : master switch for all diagnostics\n"
                + "# logToFile           : also append everything to logs/easyplace_lightfix.log\n"
                + "# traceThrottleMs     : min gap between identical [TRACE] lines\n"
                + "# snapshotThrottleMs  : min gap between [BLOCKED] snapshots\n"
                + "# unthrottledSnapshots: how many [BLOCKED] snapshots are always written in full\n"
                + "# rotateForLightBlocks : send Litematica Printer's server-side rotation when the\n"
                + "#                       Easy Place target is minecraft:light. Packet only, the\n"
                + "#                       camera does not move. Visible to the server / anticheat.\n"
                + "#                       Sent from the head of vanilla useItemOn, so it also fires\n"
                + "#                       when EasyPlaceFix makes the click instead of Litematica.\n"
                + "# rescueLightTarget   : recover the Easy Place target when Litematica discards it\n"
                + "#                       because a closer vanilla block won the ray trace.\n"
                + "#                       Rewritten path only (easyPlacePostRewrite = ON).\n"
                + "# directClickForLightBlocks : click a light block's target cell directly instead of\n"
                + "#                       using the click position Litematica computed. Independent\n"
                + "#                       of Litematica's easyPlaceClickAdjacent either way.\n"
                + "#                       Off = on easyPlacePostRewrite = ON, a light block in mid-air\n"
                + "#                       can never be placed.\n"
                + "\n"
                + "# The schematic ray trace runs every tick, so keep the throttles sane.\n"
                + "\n"
                + "debug=" + this.debug + "\n"
                + "logToFile=" + this.logToFile + "\n"
                + "traceThrottleMs=" + this.traceThrottleMs + "\n"
                + "snapshotThrottleMs=" + this.snapshotThrottleMs + "\n"
                + "unthrottledSnapshots=" + this.unthrottledSnapshots + "\n"
                + "rotateForLightBlocks=" + this.rotateForLightBlocks + "\n"
                + "rescueLightTarget=" + this.rescueLightTarget + "\n"
                + "directClickForLightBlocks=" + this.directClickForLightBlocks + "\n";

        try
        {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text);
        }
        catch (IOException e)
        {
            System.err.println("[EasyPlaceLightFix] Could not create " + path + ": " + e);
        }
    }
}