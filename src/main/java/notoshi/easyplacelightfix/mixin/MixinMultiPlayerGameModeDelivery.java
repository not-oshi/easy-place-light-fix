package notoshi.easyplacelightfix.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import notoshi.easyplacelightfix.PrinterDelivery;

/**
 * Litematica Printer's server-side rotation, sent from the head of vanilla's own
 * {@link MultiPlayerGameMode#useItemOn}.
 *
 * <h2>Why the rotation is not hooked inside Litematica</h2>
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
 * <p>A hook on the {@code useItemOn} call inside {@code handleEasyPlace} has three blind spots: the
 * legacy path never reaches it; EasyPlaceFix
 * (<a href="https://github.com/ServChan/EasyPlaceFix">ServChan/EasyPlaceFix</a>) replaces the method
 * that contains it, because its {@code MixinWorldUtils.t1} injects into
 * {@code doEasyPlaceAction} and cancels it; and EasyPlaceFix's own clicks — orientation correction,
 * tick pacing, note-block tuning, all driven from its {@code TickThread} queue — are issued from
 * somewhere else entirely.</p>
 *
 * <h2>Why HEAD is the right point</h2>
 * {@code useItemOn} is the last vanilla step before the click packet leaves, so injecting at
 * {@code HEAD} guarantees the rotation is already on the wire. It is also path-agnostic: the hook
 * fires for Litematica's rewritten path, Litematica's legacy path, EasyPlaceFix's queue, and a
 * vanilla right-click that happens to be a light block placement.
 *
 * <p>The return value is never modified. Nothing about the click is changed here — only one extra
 * packet is sent, and only for light blocks. {@link PrinterDelivery#onUseItemOn} does all the
 * deciding, including deduplicating the double entry described there.</p>
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
}