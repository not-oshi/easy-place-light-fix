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
 * the mod's behaviour, so if Litematica ever moves them the game should fail loudly rather than
 * silently degrade back to "light blocks do not place". The diagnostics mixins, which are not
 * behaviour, stay in the optional config. The Printer-style rotation is not here at all any more —
 * see {@link MixinMultiPlayerGameModeDelivery} for why it had to move.</p>
 *
 * <p>{@code EasyPlaceUtils} is a static utility class, so every handler must be {@code static}:
 * Mixin rejects a non-static callback on a static target with
 * {@code InvalidInjectionException: non-static callback method ... targets a static method}.</p>
 *
 * <h2>Why {@link #notoshi$rescueLightTarget} omits the target's arguments</h2>
 * It is an {@link Inject} at a {@code RETURN} that declares <b>only</b> a
 * {@link CallbackInfoReturnable}. That is Mixin's documented "simple" callback form and it is
 * deliberate: {@code getTargetPosition} takes a {@code RayTraceUtils$RayTraceWrapper}, a Litematica
 * type this mod cannot name at compile time. Mixin builds the callback descriptor from the
 * <b>containing</b> method ({@code Target#getCallbackDescriptor} reads {@code this.method.desc}), so
 * the target's own arguments are optional — omitting them is how a mixin with no compile-time
 * dependency on its target can still hook it.
 *
 * <p>Without that trick this mod would need Litematica on the compile classpath, which
 * {@code build.gradle} deliberately refuses, because a compile-time dependency is what turns a
 * version bump into a build break instead of a runtime warning.</p>
 *
 * <h2>Nothing here may ever throw</h2>
 * These hooks run in the middle of Litematica's own click handling, i.e. on the render thread with no
 * exception barrier of any kind. Anything thrown here propagates straight out of
 * {@code Minecraft#tick} and kills the client — the game saves and reports an "Unreported exception
 * thrown!". Each handler therefore wraps its own body in {@code try/catch (Throwable)} and falls
 * back to Litematica's stock behaviour. A diagnostic that is not worth a crashed game is not a
 * diagnostic.
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
     * <p>Only ordinal 3 is ours, so it is named explicitly rather than left to
     * {@code @At}'s default. A bare {@code @At("RETURN")} happens to land on the same instruction
     * today; that is not a property worth depending on when the alternative is one number.</p>
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
     * <h3>cancellable = true is not optional</h3>
     * {@code CallbackInfoReturnable#setReturnValue} calls {@code CallbackInfo#cancel}, and
     * {@code cancel} throws when the injection was not declared {@code cancellable}:
     *
     * <pre>
     *     org.spongepowered.asm.mixin.injection.callback.CancellationException:
     *         The call getTargetPosition is not cancellable.
     *         at CallbackInfo.cancel(CallbackInfo.java:101)
     *         at CallbackInfoReturnable.setReturnValue(CallbackInfoReturnable.java:106)
     *         at EasyPlaceUtils...notoshi$rescueLightTarget(EasyPlaceUtils.java:1562)
     * </pre>
     *
     * <p>Nothing catches it — it unwinds through {@code getTargetPosition} and
     * {@code handleEasyPlace} straight into {@code Minecraft#tick}, and the client dies. In testing
     * that was the whole of the reported "instacrash": it fired on exactly and only the clicks where
     * the rescue actually had something to return, and the block still appeared in the world because
     * the server had received an earlier successful click of the same button hold. Adding
     * {@code cancellable = true} is the entire fix; the {@code try/catch} below is the belt to that
     * suspenders, because the same unwinding path would carry any other mistake identically.</p>
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

                // Without this the snapshot would report the target lookup as never reached: the
                // debug hook that normally records getTargetPosition' answer is at ordinals 0, 1
                // and 2 only, because ordinal 3 is this instruction and cancelling here skips
                // every injection placed after ours at the same offset.
                notoshi.easyplacelightfix.Diagnostics.onTargetPosition(rescued);
            }
        }
        catch (Throwable t)
        {
            PrinterDelivery.onHookFailed("rescueLightTarget", t);
        }
    }

    /**
     * Replaces the click Litematica computed for a light block with a click on the target cell.
     *
     * <p><b>This is the hook that makes the mod work, and it is the hook 1.0–1.2 got wrong.</b> Those
     * versions injected at the {@code HEAD} of {@code getAdjacentClickPosition}, which
     * {@code getClickPosition} calls <i>only</i> when {@code EASY_PLACE_CLICK_ADJACENT} is on:</p>
     *
     * <pre>
     *     if (EASY_PLACE_CLICK_ADJACENT.getBooleanValue())
     *         return getAdjacentClickPosition(targetPos);
     *     return hitResult;
     * </pre>
     *
     * <p>So the whole mod was a silent no-op with that option off — while its own log line blamed
     * something else entirely ("check directClickForLightBlocks"). Injecting at
     * {@code ordinal = 1} of {@code getClickPosition} covers both branches at once, because that is
     * the one {@code areturn} both of them pass through.</p>
     *
     * <p><b>What the adjacent search does when it runs.</b> It exists because vanilla cannot click
     * air: it ray-traces from the camera, and if that misses it walks the target's six neighbours
     * looking for one that is <b>not</b> replaceable so it has a solid block to click the face of. A
     * light block in mid-air has no such neighbour, the loop runs out and returns {@code null} — and
     * {@code handleEasyPlace} then bails {@code FAIL} at its
     * {@code clickPos == null || hand == null} check, with a perfectly good target and a perfectly
     * good item in hand.</p>
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
     * <p>So for a {@code minecraft:light} this clicks the target cell directly instead.
     * {@code BlockItem} places into whatever {@code BlockPlaceContext#canPlace()} accepts, and for
     * air that is {@code state.canBeReplaced(context)} — true — so the server accepts a click on an
     * empty cell and fills it. Litematica Printer's "replaceable" mode does exactly this.</p>
     *
     * <p>The neighbour search still runs before the answer is discarded. It is one ray trace per light
     * block click; paying it is how the mod stops caring whether {@code easyPlaceClickAdjacent} is
     * on or off, which was the whole point.</p>
     *
     * <p>The target position comes from {@code hitResult.getBlockPos()} rather than from a parameter,
     * because {@code getClickPosition} derives it that way itself at offset 20 and it is the same
     * value in both branches. {@link BlockHitResult} and {@link BlockState} are vanilla types, so
     * naming them costs no Litematica dependency.</p>
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
            PrinterDelivery.onHookFailed("directLightClick", t);
        }
    }
}