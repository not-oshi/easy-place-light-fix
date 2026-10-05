package notoshi.easyplacelightfix;

import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reflective access to Litematica's schematic world, shared by the delivery logic and the
 * diagnostics.
 *
 * <p>The mod deliberately has no compile-time dependency on Litematica: the mixins target it by name
 * via {@code @Mixin(targets = ...)}, which works against any build of it. Anything that needs to
 * <em>call</em> Litematica has to go through reflection, and everything here fails soft — a missing
 * schematic or a restructured Litematica must never break placement.</p>
 *
 * <p>Resolved once and then cached. This sits on the click path, so a failed lookup must not turn
 * into a repeated {@code Class.forName} every click.</p>
 */
public final class SchematicAccess
{
    /**
     * Note the package: in Litematica 0.28.x the handler lives in
     * {@code fi.dy.masa.litematica.world}, <b>not</b> in {@code fi.dy.masa.litematica.schematic} as
     * in older versions. Getting it wrong is silent — the {@code ClassNotFoundException} is
     * swallowed and every lookup just reports "&lt;unavailable&gt;".
     */
    private static final String HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler";

    private static Method getSchematicWorld;
    private static boolean resolved;

    private SchematicAccess() {}

    /**
     * @return the schematic's block state at {@code pos}, or {@code null} when no schematic is
     *         loaded or Litematica could not be reached
     */
    public static BlockState tryGetState(BlockPos pos)
    {
        if (pos == null)
        {
            return null;
        }

        Method getter = resolve();

        if (getter == null)
        {
            return null;
        }

        try
        {
            Object world = getter.invoke(null);

            // WorldSchematic extends net.minecraft.world.level.Level, so this is the real thing.
            if (world instanceof Level level)
            {
                return level.getBlockState(pos);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            // No schematic loaded, or Litematica restructured. Non-fatal by design.
        }

        return null;
    }

    /** Whether a schematic is loaded at all — used to tell "no schematic" apart from "no access". */
    public static boolean isAvailable()
    {
        return resolve() != null;
    }

    private static Method resolve()
    {
        if (resolved)
        {
            return getSchematicWorld;
        }

        resolved = true;

        try
        {
            getSchematicWorld = Class.forName(HANDLER).getMethod("getSchematicWorld");
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            getSchematicWorld = null;
            System.err.println("[EasyPlaceLightFix] Cannot reach " + HANDLER
                    + " (" + e + "). Schematic lookups are disabled.");
        }

        return getSchematicWorld;
    }
}