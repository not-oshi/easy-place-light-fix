package notoshi.easyplacelightfix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * The mod's three behavioural switches, read from
 * {@code <gameDir>/config/easyplace_lightfix.properties}.
 *
 * <p>All three default to on, which is what makes the mod work without touching anything. They exist
 * so a user who hits an unexpected interaction with another mod can turn one behaviour off in
 * isolation rather than removing the mod.</p>
 */
public final class ModConfig
{
    private static final String FILE_NAME = "easyplace_lightfix.properties";

    /**
     * Send Litematica Printer's server-side rotation before placing a {@code minecraft:light}.
     *
     * <p>Packet only — the local player's yaw and pitch are never touched, so the camera does not
     * move. The server does briefly see a look direction the client never rendered, which any
     * anti-cheat tracking look direction can see. Turn off if that matters more than placement
     * succeeding.</p>
     */
    public boolean rotateForLightBlocks = true;

    /**
     * Recover the Easy Place target when Litematica discards it because a closer vanilla block won
     * the ray.
     *
     * <p>Only ever substitutes a {@code minecraft:light} found by Litematica's own schematic-only
     * trace, at Litematica's own block range. Without it, Easy Place returns {@code FAIL} before it
     * ever builds a click.</p>
     */
    public boolean rescueLightTarget = true;

    /**
     * Click a light block's target cell directly instead of using the click position Litematica
     * computed.
     *
     * <p>This is the switch that makes placement work. Litematica has to click an existing block
     * because vanilla cannot click air; a light block in mid-air has no solid neighbour, the search
     * returns {@code null}, and Easy Place reports {@code FAIL} while holding a valid target and a
     * valid item.</p>
     *
     * <p>Only applied when the target is a {@code minecraft:light} in the loaded schematic
     * <b>and</b> the cell is still empty in the client world. Every other block, and every light cell
     * that is already occupied, keeps stock behaviour.</p>
     */
    public boolean directClickForLightBlocks = true;

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

        if (!Files.exists(path))
        {
            writeDefault(path);

            return this;
        }

        Properties props = new Properties();

        try (InputStream in = Files.newInputStream(path))
        {
            props.load(in);
            this.rotateForLightBlocks = bool(props, "rotateForLightBlocks", this.rotateForLightBlocks);
            this.rescueLightTarget = bool(props, "rescueLightTarget", this.rescueLightTarget);
            this.directClickForLightBlocks =
                    bool(props, "directClickForLightBlocks", this.directClickForLightBlocks);
        }
        catch (IOException | IllegalArgumentException e)
        {
            System.err.println("[EasyPlaceLightFix] Could not read " + path + ", using defaults: " + e);
        }

        return this;
    }

    private static boolean bool(Properties props, String key, boolean def)
    {
        String raw = props.getProperty(key);

        return raw == null ? def : Boolean.parseBoolean(raw.trim());
    }

    private void writeDefault(Path path)
    {
        String text = ""
                + "# Easy Place Light Fix - all three default to true, which is what makes the mod work.\n"
                + "# Turn one off only if it conflicts with another mod.\n"
                + "\n"
                + "# rotateForLightBlocks : send a rotation packet before placing a light block.\n"
                + "#                       Packet only, the camera does not move, but the server\n"
                + "#                       briefly sees a look direction the client never rendered.\n"
                + "# rescueLightTarget    : recover the Easy Place target when Litematica discards it\n"
                + "#                       because a closer vanilla block won the ray.\n"
                + "# directClickForLightBlocks : click a light block's target cell directly instead of\n"
                + "#                       using the click position Litematica computed. Turning this\n"
                + "#                       off means a light block in mid-air cannot be placed.\n"
                + "\n"
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
