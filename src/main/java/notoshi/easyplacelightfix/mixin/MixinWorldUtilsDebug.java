package notoshi.easyplacelightfix.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import notoshi.easyplacelightfix.Diagnostics;

/**
 * Read-only diagnostics for Litematica's legacy Easy Place path
 * ({@code easyPlacePostRewrite = false}).
 *
 * <p>This is the path <b>EasyPlaceFix replaces</b>, so it is also the path where our own mod used to
 * be completely inert. From Litematica 0.28.8 {@code WorldUtils#handleEasyPlace}:</p>
 *
 * <pre>
 *      9: EASY_PLACE_POST_REWRITE.getBooleanValue()
 *     15: ifne 104        // ON -&gt; return false, doEasyPlaceAction is never called
 *     27: invokestatic doEasyPlaceAction(mc)
 * </pre>
 *
 * <p>EasyPlaceFix's only entry point is an injection into {@code doEasyPlaceAction}, so
 * {@code easyPlacePostRewrite = true} makes it inert and {@code = false} makes this mod's
 * Litematica-side hooks inert. The two settings are mutually exclusive by construction — see the
 * README, which explains what to set instead.</p>
 *
 * <p>Lives in a {@code required: false} mixin config, so if Litematica ever renames these methods
 * the mod keeps working and only the diagnostics go silent.</p>
 *
 * <p>{@code WorldUtils} is a static utility class, so every handler below must be {@code static} as
 * well — Mixin rejects a non-static callback on a static target.</p>
 */
@Mixin(targets = "fi.dy.masa.litematica.util.WorldUtils")
public class MixinWorldUtilsDebug
{
    /**
     * Descriptor of the legacy action. Observed only at its {@code HEAD}, never at a return site.
     *
     * <p>It has <b>eleven</b> {@code areturn} sites in 0.28.8 (offsets 69, 77, 155, 165, 195, 217,
     * 247, 432, 703, 730, 734). 1.3.0 redirected the single call site instead — there is only one, at
     * {@code handleEasyPlace} offset 27 — and the handler was rejected:</p>
     *
     * <pre>
     *     Found unexpected argument type net.minecraft.world.InteractionResult at index 1,
     *     expected net.minecraft.client.Minecraft
     *     Expected signature: (Lnet/minecraft/client/Minecraft;
     *                            Lnet/minecraft/client/Minecraft;)Lnet/minecraft/world/InteractionResult;
     * </pre>
     *
     * <p>That aborted this whole mixin class, so a tester session recorded no legacy-path diagnostics
     * at all. {@link #notoshi$legacyEntered} marks the attempt and the eleven
     * {@code notoshi$legacyRetN} hooks read the verdict;
     * {@link #notoshi$debugLegacyMessageShown} remains only to catch the case where the
     * {@code FAIL} was produced by something other than a return we can see.</p>
     */
    private static final String LEGACY_ACTION =
            "doEasyPlaceAction(Lnet/minecraft/client/Minecraft;)Lnet/minecraft/world/InteractionResult;";

    /**
     * The legacy path was reached.
     *
     * <p>Resets the per-click state, exactly as {@code notoshi$attemptStart} does for the rewritten
     * path, so a snapshot can never mix in values left behind by an earlier post-rewrite click.
     * Without this the two paths share one set of fields, and switching the Litematica option
     * mid-session produced snapshots whose {@code areturn site} described a method that was not
     * running — which is exactly what the tester hit, in the opposite direction: the startup banner
     * said {@code easyPlacePostRewrite = OFF} while {@code [CLICK] post-rewrite} lines were printing
     * seconds later.</p>
     */
    @Inject(method = LEGACY_ACTION, at = @At("HEAD"))
    private static void notoshi$legacyEntered(Minecraft mc,
                                              CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyAttempt(mc);
    }

    /**
     * The eleven {@code areturn} sites of {@code doEasyPlaceAction}, each pinned by ordinal.
     *
     * <p><b>1.3.1 observed none of them</b> and the tester log showed exactly what that costs:
     * {@code finished=0 blocked=0} while the method rejected clicks for eighty seconds and every
     * snapshot said {@code areturn site : <legacy path: no per-branch hooks exist>}. The argument
     * for skipping them was that the legacy path needs no click fix, which is true and irrelevant —
     * a path whose rejection the log cannot name is not diagnosed, and four counters reading zero
     * next to a screen full of {@code easy_place_fail} is the exact false report this mod exists to
     * prevent.</p>
     *
     * <p>These are plain {@code @Inject}s, not {@code @Redirect}s, for the same reason as in
     * {@code MixinEasyPlaceUtilsDebug}: a redirect handler on a static target cannot take the
     * original return value as a parameter and cannot call the target without recursing, so every
     * attempt was rejected and took the whole class down. Nothing here modifies control flow.</p>
     *
     * <p>Ordinals index {@code Diagnostics.LEGACY_RETURN_SITES} directly, so the number in the
     * mixin <em>is</em> the bytecode offset's ordinal and the two cannot drift.</p>
     */
    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 0))
    private static void notoshi$legacyRet0(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(0, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 1))
    private static void notoshi$legacyRet1(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(1, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 2))
    private static void notoshi$legacyRet2(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(2, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 3))
    private static void notoshi$legacyRet3(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(3, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 4))
    private static void notoshi$legacyRet4(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(4, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 5))
    private static void notoshi$legacyRet5(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(5, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 6))
    private static void notoshi$legacyRet6(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(6, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 7))
    private static void notoshi$legacyRet7(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(7, cir.getReturnValue());
    }

    /** The return a completed click leaves by, and also the one an empty build item jumps to. */
    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 8))
    private static void notoshi$legacyRet8(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(8, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 9))
    private static void notoshi$legacyRet9(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(9, cir.getReturnValue());
    }

    @Inject(method = LEGACY_ACTION, at = @At(value = "RETURN", ordinal = 10))
    private static void notoshi$legacyRet10(Minecraft mc, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onLegacyResult(10, cir.getReturnValue());
    }

    /**
     * The legacy path's only {@code FAIL}: {@code handleEasyPlace} returns {@code true} right before
     * it prints {@code litematica.message.easy_place_fail}. Three {@code ireturn} sites in 0.28.8
     * (offsets 90, 103, 105), all pinned.
     *
     * <p>1.3.1 also owned the snapshot from here, because {@code doEasyPlaceAction} had no return
     * hooks. It now only records <i>that</i> a failure was reported and whether one of
     * {@code doEasyPlaceAction}'s own returns produced it; the branch, the counter and the snapshot
     * all come from {@link Diagnostics#onLegacyResult}, so they cannot disagree.</p>
     */
    @Inject(
            method = "handleEasyPlace(Lnet/minecraft/client/Minecraft;)Z",
            at = @At(value = "RETURN", ordinal = 0)
    )
    private static void notoshi$debugLegacyMessageShown(Minecraft mc, CallbackInfoReturnable<Boolean> cir)
    {
        boolean failed = Boolean.TRUE.equals(cir.getReturnValue());

        Diagnostics.onEasyPlaceWithMessage(
                failed ? Diagnostics.LEGACY_MESSAGE_SHOWN : Diagnostics.LEGACY_MESSAGE_NOT_SHOWN);

        if (failed)
        {
            // If no doEasyPlaceAction return site was seen, the FAIL came from somewhere else:
            // EasyPlaceFix cancels the method at its getHitType() call (offset 79). 1.3.1 could not
            // tell those two cases apart and so could not answer "why is easyplacefix behaving
            // strangely" - both looked like "doEasyPlaceAction said FAIL".
            Diagnostics.onLegacyAnsweredElsewhere();
        }
    }

    @Inject(
            method = "handleEasyPlace(Lnet/minecraft/client/Minecraft;)Z",
            at = @At(value = "RETURN", ordinal = 1)
    )
    private static void notoshi$debugLegacyMessageSilent(Minecraft mc, CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onEasyPlaceWithMessage(
                Boolean.TRUE.equals(cir.getReturnValue())
                        ? Diagnostics.LEGACY_MESSAGE_CONSUMED
                        : Diagnostics.LEGACY_MESSAGE_NOT_SHOWN);
    }

    @Inject(
            method = "handleEasyPlace(Lnet/minecraft/client/Minecraft;)Z",
            at = @At(value = "RETURN", ordinal = 2)
    )
    private static void notoshi$debugLegacyUnhandled(Minecraft mc, CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onEasyPlaceWithMessage(
                Boolean.TRUE.equals(cir.getReturnValue())
                        ? Diagnostics.LEGACY_MESSAGE_CONSUMED
                        : Diagnostics.LEGACY_MESSAGE_NOT_CONSUMED);
    }

    /**
     * Notes that the hold-to-place tick path is being evaluated at all (throttled internally).
     *
     * <p><b>The {@code CallbackInfo} parameter is mandatory.</b> Mixin always appends it to the
     * handler signature, even for {@code @Inject} into a {@code void} method. Leaving it out makes
     * the injector throw:</p>
     *
     * <pre>
     *     InvalidInjectionException: Invalid descriptor on ...notoshi$debugLegacyTick
     *     Expected (Lnet/minecraft/client/Minecraft;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V
     *     but found (Lnet/minecraft/client/Minecraft;)V
     * </pre>
     *
     * <p>That exception aborts the application of the <b>whole</b> mixin class, which silently
     * disabled the other injections in this class as well. It is only a warning because this mixin
     * config is {@code required: false}.</p>
     *
     * <p>The {@code ordinal = 0} is redundant — {@code easyPlaceOnUseTick} is {@code void} with a
     * single {@code return} at offset 62, so a bare {@code @At("RETURN")} has only one instruction
     * to mean. It is pinned anyway, because the rule in {@code MixinEasyPlaceUtilsDebug} is that
     * every {@code @At} in this mod names its ordinal, and one exception is how the 1.2.0 ordinal bug
     * looked the moment before it was a bug.</p>
     *
     * <p>Worth knowing what this method actually gates, from its bytecode: it calls
     * {@code doEasyPlaceAction} only when {@code EASY_PLACE_MODE} <b>and</b>
     * {@code EASY_PLACE_HOLD_ENABLED} are on, the activation key is held, the tool mode is not
     * REBUILD — and {@code EASY_PLACE_POST_REWRITE} is <b>false</b> (offset 51: {@code ifne 62},
     * skipping the call). So this line printing at all is a direct, independent confirmation that
     * the legacy path is the live one.</p>
     */
    @Inject(
            method = "easyPlaceOnUseTick(Lnet/minecraft/client/Minecraft;)V",
            at = @At(value = "RETURN", ordinal = 0)
    )
    private static void notoshi$debugLegacyTick(Minecraft mc, CallbackInfo ci)
    {
        Diagnostics.onLegacyTickPath();
    }

    /**
     * The legacy {@code placementRestrictionInEffect(Minecraft)} overload is deliberately not observed
     * per return site.
     *
     * <p>1.2.1 injected at a bare {@code @At("RETURN")} — one of eight {@code ireturn} sites,
     * whichever the default picked. 1.3.0 redirected its two call sites instead and that handler was
     * rejected too (same static-redirect rule as in {@code MixinEasyPlaceUtilsDebug}), taking the
     * class down again. Neither is needed: both call sites in {@code doEasyPlaceAction} (offsets 60
     * and 715) are immediately followed by a return that {@link #notoshi$legacyRet0} and
     * {@link #notoshi$legacyRet9} already report, and the method's only other job here is refreshing
     * the vanilla crosshair readout, which {@link #notoshi$legacyEntered} does.</p>
     *
     * <p>Note what is <b>not</b> in {@code LEGACY_RETURN_SITES}: no "no solid neighbour to click".
     * That check only exists on the rewritten path, in {@code getAdjacentClickPosition}. The legacy
     * path builds its hit at offset 530 straight from the target cell, which is why a mid-air light
     * block needs no click fix there — see the README.</p>
     */
}