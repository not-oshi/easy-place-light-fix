package notoshi.easyplacelightfix.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.InteractionResult;

import notoshi.easyplacelightfix.Diagnostics;
import notoshi.easyplacelightfix.PrinterDelivery;

/**
 * Litematica Printer's server-side rotation, sent from the head of vanilla's own
 * {@link MultiPlayerGameMode#useItemOn}, plus the click's real verdict on the way out.
 *
 * <p><b>This hook moved here in 1.3.0, and that move is the whole reason this mod now composes with
 * EasyPlaceFix instead of being silently excluded by it.</b></p>
 *
 * <h2>Why it could not stay inside Litematica</h2>
 * Litematica ships two independent Easy Place implementations and one option chooses between them.
 * From {@code WorldUtils#handleEasyPlace}, Litematica 0.28.8 bytecode:
 *
 * <pre>
 *      0: EASY_PLACE_MODE.getBooleanValue()
 *      6: ifeq 104
 *      9: EASY_PLACE_POST_REWRITE.getBooleanValue()
 *     15: ifne 104        // ON  -&gt; return false, doEasyPlaceAction never runs
 *     18: getToolMode() != REBUILD -&gt; return false
 *     27: invokestatic doEasyPlaceAction(mc)
 * </pre>
 *
 * <ul>
 *   <li>{@code easyPlacePostRewrite = true} → {@code EasyPlaceUtils#handleEasyPlace} does
 *       everything, including its own {@code useItemOn} call at bytecode offset <b>754</b> of that
 *       method (plus a second one at 938, for the double-slab merge);</li>
 *   <li>{@code easyPlacePostRewrite = false} → {@code WorldUtils#doEasyPlaceAction} does, with its
 *       own {@code useItemOn} call at bytecode offset <b>558</b> of <i>that</i> method.</li>
 * </ul>
 *
 * <p>1.0–1.3.0's comments and the README said 558 for the rewritten path too; that was the legacy
 * method's offset, copied across. The number only matters for anyone re-deriving the branch table,
 * so it is stated correctly here.</p>
 *
 * <p>EasyPlaceFix (<a href="https://github.com/ServChan/EasyPlaceFix">ServChan/EasyPlaceFix</a>)
 * hooks only the second one: {@code MixinWorldUtils.t1} injects into
 * {@code doEasyPlaceAction} and cancels it. Everything that mod offers — orientation correction, tick
 * pacing, note-block tuning — then runs from its own {@code TickThread} queue, calling
 * {@code interactionManager.useItemOn(...)} from Java code.</p>
 *
 * <p>A redirect on the {@code useItemOn} call <i>inside</i> {@code handleEasyPlace} therefore has
 * three blind spots: the legacy path never reaches it, EasyPlaceFix replaces the method that contains
 * it, and EasyPlaceFix's own clicks are made from somewhere else entirely. In testing that showed up
 * as "the mod only works with easyPlaceClickAdjacent on" plus "EasyPlaceFix stops working once this
 * mod is installed" — the two symptoms are one bug seen from two sides.</p>
 *
 * <h2>Why HEAD is the right point</h2>
 * {@code useItemOn} is the last vanilla step before the click packet leaves, so injecting at
 * {@code HEAD} guarantees the rotation is already on the wire. It also means the hook sees the click
 * no matter who issued it: Litematica's rewritten path, Litematica's legacy path, EasyPlaceFix's
 * queue, or a vanilla right-click that happens to be a light block placement.</p>
 *
 * <p>The handler is an {@link Inject} rather than a {@code @Redirect} because the return value is
 * left completely alone. Nothing about the click is modified here — only one extra packet is sent,
 * and only for light blocks. {@link PrinterDelivery#onUseItemOn} does all the deciding, including
 * deduplicating the double entry described there.</p>
 *
 * <h2>Why the two {@code RETURN} hooks exist at all</h2>
 * Litematica does not report whether the block went down. {@code EasyPlaceUtils#handleEasyPlace}
 * inspects only the answer of {@code useItemOn} at offset 761 and branches as:</p>
 *
 * <pre>
 *     759: aload 29
 *     761: getstatic InteractionResult.PASS
 *     764: if_acmpne 956     // useItemOn != PASS -&gt; bare PASS return (ordinal 8)
 *     ...
 *     952: getstatic SUCCESS
 *     955: areturn           // ordinal 7
 *     956: getstatic PASS
 *     959: areturn           // ordinal 8
 * </pre>
 *
 * <p>and {@code BlockItem#place} answers {@code InteractionResult.SUCCESS} when it places
 * (offset 268) and {@code FAIL} on every failure path — it never answers {@code PASS}. So
 * {@code handleEasyPlace} returning {@code PASS} means <b>the block is down</b>, and returning
 * {@code SUCCESS} means <b>nothing happened</b>. 1.0–1.3.0 counted that pair the other way round and
 * reported {@code placed=0} for a session that placed 19 blocks.</p>
 *
 * <p>Reading the verdict straight off {@code useItemOn} is the only way to get it right, and
 * {@code useItemOn} is a vanilla method whose shape is known exactly — two {@code areturn} sites,
 * offsets 27 and 67. So both are pinned:</p>
 *
 * <pre>
 *     27: areturn    InteractionResult.FAIL   - the click is outside the world border
 *     67: areturn    the result of startPrediction(...)  - SUCCESS / CONSUME / PASS / FAIL / null
 * </pre>
 *
 * <p>{@code null} at offset 67 is a real possibility, not a defensive guess: the value comes out of
 * a {@code MutableObject} that the predicted action writes, and nothing writes it if the action
 * never runs. That would otherwise be indistinguishable from a refusal.</p>
 *
 * <p>The descriptor below is the same string the 1.0–1.2 redirect used, verified against a live
 * client, so it is known-good rather than freshly guessed.</p>
 */
@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameModeDelivery
{
    /** The one {@code useItemOn} descriptor this class cares about. */
    private static final String USE_ITEM_ON =
            "useItemOn(Lnet/minecraft/client/player/LocalPlayer;"
                    + "Lnet/minecraft/world/InteractionHand;"
                    + "Lnet/minecraft/world/phys/BlockHitResult;)"
                    + "Lnet/minecraft/world/InteractionResult;";

    /**
     * Sends the fake rotation before the click goes out, for light blocks only.
     *
     * <p>Never cancels and never touches the return value: if anything goes wrong inside, the click
     * still goes out with the camera's real rotation, which is the stock behaviour and strictly
     * better than a broken game. {@link PrinterDelivery#onUseItemOn} has its own
     * {@code try/catch} around the whole body as well.</p>
     */
    @Inject(method = USE_ITEM_ON, at = @At("HEAD"))
    private void notoshi$rotateBeforeClick(LocalPlayer player,
                                          InteractionHand hand,
                                          BlockHitResult hitResult,
                                          CallbackInfoReturnable<InteractionResult> cir)
    {
        PrinterDelivery.onUseItemOn(player, hitResult);
    }

    /**
     * Bytecode offset 27 — {@code useItemOn} bailed before touching the world because the clicked
     * cell is outside the world border. Nothing was placed and nothing was sent.
     */
    @Inject(method = USE_ITEM_ON, at = @At(value = "RETURN", ordinal = 0))
    private void notoshi$clickOutOfBorder(LocalPlayer player,
                                          InteractionHand hand,
                                          BlockHitResult hitResult,
                                          CallbackInfoReturnable<InteractionResult> cir)
    {
        PrinterDelivery.onUseItemOnResult(player, hitResult, cir.getReturnValue(), 0);
    }

    /**
     * Bytecode offset 67 — the real verdict of the click.
     *
     * <p>This is the outer vanilla right-click as often as it is Litematica's own nested call: for a
     * light block {@code useItemOn} is entered twice per tick, and the one that matters is the last
     * one to return before {@code handleEasyPlace} returns. Overwriting rather than accumulating is
     * therefore correct — {@link Diagnostics} keeps only the most recent verdict.</p>
     */
    @Inject(method = USE_ITEM_ON, at = @At(value = "RETURN", ordinal = 1))
    private void notoshi$clickResult(LocalPlayer player,
                                     InteractionHand hand,
                                     BlockHitResult hitResult,
                                     CallbackInfoReturnable<InteractionResult> cir)
    {
        PrinterDelivery.onUseItemOnResult(player, hitResult, cir.getReturnValue(), 1);
    }
}