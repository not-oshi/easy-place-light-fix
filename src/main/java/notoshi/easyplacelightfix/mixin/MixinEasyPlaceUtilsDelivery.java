package notoshi.easyplacelightfix.mixin;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import notoshi.easyplacelightfix.PrinterDelivery;

/**
 * "Easy Place decides, Printer delivers" — the two hooks that implement it, both of them inside
 * Litematica's rewritten Easy Place path.
 *
 * <p>Both are gated on the target being {@code minecraft:light}, so every other block keeps
 * byte-for-byte Litematica behaviour.</p>
 *
 * <p>This class lives in the {@code required: true} mixin config on purpose: these hooks <em>are</em>
 * the mod's behaviour, so if Litematica moves them the game fails loudly instead of silently
 * degrading back to "light blocks do not place". The Printer-style rotation is sent from vanilla
 * instead — see {@link MixinMultiPlayerGameModeDelivery}.</p>
 *
 * <p>{@code EasyPlaceUtils} is a static utility class, so every handler must be {@code static}:
 * Mixin rejects a non-static callback on a static target with
 * {@code InvalidInjectionException: non-static callback method ... targets a static method}. A
 * rejected descriptor aborts the whole mixin class, not just one handler.</p>
 *
 * <h2>Why {@link #notoshi$rescueLightTarget} omits the target's arguments</h2>
 * It is an {@link Inject} at a {@code RETURN} declaring <b>only</b> a
 * {@link CallbackInfoReturnable}: Mixin's documented "simple" callback form.
 * {@code getTargetPosition} takes a {@code RayTraceUtils$RayTraceWrapper}, a Litematica type this
 * mod cannot name at compile time, and Mixin builds the callback descriptor from the
 * <b>containing</b> method ({@code Target#getCallbackDescriptor} reads {@code this.method.desc}), so
 * the target's own arguments are optional. Omitting them lets a mixin with no compile-time
 * dependency on its target still hook it.
 *
 * <p>That keeps Litematica off the compile classpath, which {@code build.gradle} refuses on
 * purpose: a compile-time dependency turns a version bump into a build break instead of a runtime
 * warning.</p>
 *
 * <h2>Nothing here may ever throw</h2>
 * These hooks run inside Litematica's own click handling, i.e. on the render thread with no
 * exception barrier. Anything thrown propagates straight out of {@code Minecraft#tick} and kills
 * the client. Each handler therefore wraps its body in {@code try/catch (Throwable)} and falls back
 * to Litematica's stock behaviour.
 */
@Mixin(targets = "fi.dy.masa.litematica.util.EasyPlaceUtils")
public class MixinEasyPlaceUtilsDelivery
{
    /**
     * Descriptor of {@code getTargetPosition}. Its argument is a Litematica type and stays unnamed.
     */
    private static final String GET_TARGET = "getTargetPosition(Lfi/dy/masa/litematica/util/"
            + "RayTraceUtils$RayTraceWrapper;)Lnet/minecraft/world/phys/BlockHitResult;";

    /**
     * Descriptor of {@code getClickPosition}.
     *
     * <p>Litematica 0.28.8 bytecode, {@code private static BlockHitResult getClickPosition(
     * BlockHitResult hitResult, BlockState state, BlockState schematicState)}:</p>
     *
     * <pre>
     *      0-18:  if (state.getBlock() instanceof SlabBlock)
     *                  return getClickPositionForSlab(hitResult, state, schematicState);   // areturn #0
     *     19-23:  BlockPos targetPos = hitResult.getBlockPos();
     *     25-31:  boolean clickAdjacent = EASY_PLACE_CLICK_ADJACENT.getBooleanValue();
     *     33-43:  if (clickAdjacent)
     *                  return getAdjacentClickPosition(targetPos);   // value stays on the stack
     *     46-47:  return hitResult;                                                  // areturn #1
     * </pre>
     *
     * <p>Exactly two {@code areturn} sites, so {@code ordinal = 1} is unambiguous — and it is the
     * one that carries <b>both</b> branches. A light block is never a {@code SlabBlock}, so ordinal 0
     * never applies to us.</p>
     */
    private static final String GET_CLICK = "getClickPosition(Lnet/minecraft/world/phys/BlockHitResult;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/block/state/BlockState;)"
            + "Lnet/minecraft/world/phys/BlockHitResult;";

    /**
     * Hands the Easy Place target back when Litematica threw it away.
     *
     * <p>{@code getTargetPosition} has four {@code areturn} sites in 0.28.8:</p>
     *
     * <pre>
     *   ordinal 0 / offset  23   return null                        (mc.player == null)
     *   ordinal 1 / offset 142   placement-position-handler branch  (Carpet et al.)
     *   ordinal 2 / offset 161   return trace.getBlockHitResult()   the normal schematic hit
     *   ordinal 3 / offset 163   return null                        a vanilla block won the ray
     * </pre>
     *
     * <p>Only ordinal 3 is ours, so it is named explicitly: every {@code @At} in this class pins an
     * ordinal, because a bare {@code @At("RETURN")} defaulting onto a particular instruction is
     * not a property worth depending on when the alternative is one number.</p>
     *
     * <p>At ordinal 3 {@code handleEasyPlace} goes on to return {@link
     * net.minecraft.world.InteractionResult#FAIL} at its {@code areturn} ordinal 1 whenever the
     * closest thing along the ray was a <b>vanilla</b> block instead of a schematic block. A light
     * block is a full cube with no collidable outline of its own, so it loses that comparison to
     * whatever ordinary geometry sits in front of it — the exact situation this mod exists for. When
     * the value is {@code null}, {@link PrinterDelivery#rescueLightTarget()} re-runs Litematica's own
     * schematic-only trace and, if the block it finds really is a {@code minecraft:light},
     * substitutes that hit.</p>
     *
     * <p>The substituted value is still Litematica's decision — the same
     * {@code RayTraceUtils#traceToSchematicWorld} that {@code getGenericTrace} itself used, with the
     * same block range. Nothing here decides <em>where</em> to place; it only recovers a target
     * Litematica found and then discarded.</p>
     *
     * <h3>cancellable = true is required</h3>
     * {@code CallbackInfoReturnable#setReturnValue} calls {@code CallbackInfo#cancel}, and
     * {@code cancel} throws when the injection was not declared {@code cancellable}:
     *
     * <pre>
     *     org.spongepowered.asm.mixin.injection.callback.CancellationException:
     *         The call getTargetPosition is not cancellable.
     *         at CallbackInfo.cancel(CallbackInfo.java:101)
     *         at CallbackInfoReturnable.setReturnValue(CallbackInfoReturnable.java:106)
     * </pre>
     *
     * <p>Nothing catches it — it unwinds through {@code getTargetPosition} and
     * {@code handleEasyPlace} straight into {@code Minecraft#tick} and the client dies. The
     * {@code try/catch} below is belt to that suspenders, since the same unwinding path would carry
     * any other mistake identically.</p>
     *
     * <p>The ordinal-3 site is the only one of the four that is ours: offsets 23, 142 and 161 are
     * the null-player guard, the Carpet placement-position handler and the normal schematic hit
     * respectively.</p>
     */
    @Inject(method = GET_TARGET, at = @At(value = "RETURN", ordinal = 3), cancellable = true)
    private static void notoshi$rescueLightTarget(CallbackInfoReturnable<BlockHitResult> cir)
    {
        try
        {
            if (cir.getReturnValue() != null)
            {
                return;
            }

            BlockHitResult rescued = PrinterDelivery.rescueLightTarget();

            if (rescued != null)
            {
                cir.setReturnValue(rescued);
            }
        }
        catch (Throwable t)
        {
            // Same reasoning as in notoshi$directLightClick: this runs inside Litematica's click
            // handling with no exception barrier, so anything thrown would unwind into
            // Minecraft#tick. Litematica's own answer is the fallback.
        }
    }

    /**
     * Replaces the click Litematica computed for a light block with a click on the target cell.
     *
     * <p><b>This is the hook that makes the mod work.</b> It hooks {@code getClickPosition} rather
     * than {@code getAdjacentClickPosition} because the latter only runs on the
     * {@code easyPlaceClickAdjacent=ON} branch:</p>
     *
     * <pre>
     *     if (EASY_PLACE_CLICK_ADJACENT.getBooleanValue())
     *         return getAdjacentClickPosition(targetPos);
     *     return hitResult;
     * </pre>
     *
     * <p>Hooking it there would make the mod a silent no-op with the option off.
     * {@code getClickPosition} is the single point both branches pass through, so injecting at
     * {@code ordinal = 1} — the one {@code areturn} they share — covers both at once and the option
     * makes no difference.</p>
     *
     * <p><b>What the adjacent search does.</b> It exists because vanilla cannot click air: it
     * ray-traces from the camera, and if that misses it walks the target's six neighbours looking
     * for one that is <b>not</b> replaceable so it has a solid block to click the face of. A light
     * block in mid-air has no such neighbour, the loop runs out and returns {@code null} — and
     * {@code handleEasyPlace} then bails {@code FAIL} at its
     * {@code clickPos == null || hand == null} check, with a good target and a good item in
     * hand.</p>
     *
     * <pre>
     *     for (Direction dir : Direction.values()) {
     *         BlockPos neighbour = targetPos.relative(dir);
     *         if (!PlacementUtils.isReplaceable(level, neighbour, false))
     *             return new BlockHitResult(..., neighbour, false);
     *     }
     *     return null;                     // &lt;- mid-air light block lands here
     * </pre>
     *
     * <p>For a {@code minecraft:light} this clicks the target cell directly instead.
     * {@code BlockItem} places into whatever {@code BlockPlaceContext#canPlace()} accepts, and for
     * air that is {@code state.canBeReplaced(context)} — true — so the server accepts a click on an
     * empty cell and fills it. Litematica Printer's "replaceable" mode does exactly this.</p>
     *
     * <p>The substitution applies only when the target is a {@code minecraft:light} in the loaded
     * schematic <em>and</em> the cell is still empty in the client world; every other block keeps
     * stock behaviour. The neighbour search still runs before the answer is discarded — one ray
     * trace per light block click, paid in exchange for the option-independence above.</p>
     *
     * <p>The target position comes from {@code hitResult.getBlockPos()} rather than from a
     * parameter, because {@code getClickPosition} derives it that way itself at offset 20 and it is
     * the same value in both branches. {@link BlockHitResult} and {@link BlockState} are vanilla
     * types, so naming them costs no Litematica dependency.</p>
     */
    @Inject(method = GET_CLICK, at = @At(value = "RETURN", ordinal = 1), cancellable = true)
    private static void notoshi$directLightClick(BlockHitResult hitResult,
            BlockState state,
            BlockState schematicState,
            CallbackInfoReturnable<BlockHitResult> cir)
    {
        try
        {
            if (hitResult == null)
            {
                return;
            }

            BlockHitResult hit = PrinterDelivery.directLightClick(hitResult.getBlockPos());

            if (hit != null)
            {
                cir.setReturnValue(hit);
            }
        }
        catch (Throwable t)
        {
            // This hook runs inside Litematica's click handling, on the render thread, with no
            // exception barrier between it and Minecraft#tick. Anything thrown propagates straight
            // out and kills the client, so swallow it and fall back to Litematica's own click.
        }
    }
}