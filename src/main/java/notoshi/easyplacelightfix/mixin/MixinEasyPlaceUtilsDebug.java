package notoshi.easyplacelightfix.mixin;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import notoshi.easyplacelightfix.Diagnostics;

/**
 * Read-only diagnostics for Litematica's rewritten Easy Place path
 * ({@code easyPlacePostRewrite = true}).
 *
 * <p>Lives in a {@code required: false} mixin config, so if Litematica ever renames these methods
 * the mod keeps working and only the diagnostics go silent.</p>
 *
 * <p>{@code EasyPlaceUtils} is a static utility class, so every handler below must be {@code static}
 * as well — Mixin rejects a non-static callback on a static target.</p>
 *
 * <h2>Why so many injection points</h2>
 * {@code EasyPlaceUtils#handleEasyPlace()} can bail out with {@link InteractionResult#FAIL} from
 * <b>six</b> different places, all of them returning the very same constant, so the return value
 * alone says nothing about the reason. The {@code areturn} instructions of that method in
 * litematica-fabric-26.2-0.28.8.jar sit at bytecode offsets
 * {@code 79, 123, 127, 185, 231, 276, 580, 955, 959}; pinning each one with
 * {@code @At(value = "RETURN", ordinal = n)} turns "it said FAIL" into "it said FAIL <i>because</i>".
 * The supporting hooks below record the values those branches are testing, so the snapshot in
 * {@code [BLOCKED]} explains the verdict rather than just repeating it.
 *
 * <h3>Every {@code @At} in this class states its ordinal</h3>
 * A bare {@code @At("RETURN")} is not allowed in this mod. A default ordinal silently resolves to
 * whichever return the injector reaches first, so a hook meant for one branch can report another
 * branch's verdict, duplicate another hook's instruction, or oscillate between a real value and a
 * sentinel on the same click. Every injection point below therefore names an explicit ordinal.
 *
 * <h3>Static targets get {@code @Inject}, never {@code @Redirect}</h3>
 * Mixin builds a {@code @Redirect} handler's signature from the target's parameters and its
 * <b>return type</b>, and does not pass the original return value in as a parameter. A handler for a
 * {@code static} target that takes a boolean parameter is therefore rejected outright, and calling
 * the target from inside the handler re-enters the redirect rather than returning the original
 * value. There is no signature that both observes the verdict and preserves the original behaviour,
 * so {@code placementRestrictionInEffect()} and {@code canPlaceBlock()} are observed the direct way:
 * one pinned {@code @Inject} per return site.
 *
 * <p>A rejected handler descriptor aborts the whole mixin class, so a single bad signature disables
 * every hook in this file, not just its own. Because the config is {@code required: false}, the only
 * symptom is one {@code WARN} line, which is why handler signatures here are kept plain.</p>
 *
 * <p>Where a helper's verdict is not needed it is simply not observed. {@code placementRestrictionInEffect()}
 * has three call sites — {@code handleEasyPlace} offsets 70 and 108, each immediately followed by
 * the {@code FAIL} returns already pinned as ordinals 0 and 1, and
 * {@code handlePlacementRestriction}, pinned once — so a {@code true} verdict cannot hide anywhere
 * and needs no counter.</p>
 *
 * <h3>Two sites deliberately belong to the behaviour mixin</h3>
 * {@code getTargetPosition} ordinal 3 and {@code getClickPosition} ordinal 1 are the two
 * instructions that {@link MixinEasyPlaceUtilsDelivery} cancels. An injection placed after a
 * cancelling one at the same offset never runs, so a diagnostic hook there would be dead code that
 * looked alive. {@code PrinterDelivery} records the substituted value itself instead.</p>
 */
@Mixin(targets = "fi.dy.masa.litematica.util.EasyPlaceUtils")
public class MixinEasyPlaceUtilsDebug
{
    /**
     * Descriptor of the rewritten entry point. Everything below hooks into this method or into a
     * helper it calls, and the return values of its {@code areturn} sites are the branch IDs.
     */
    private static final String HANDLE = "handleEasyPlace()Lnet/minecraft/world/InteractionResult;";

    /** Whether Easy Place is armed at all. One {@code ireturn} site, pinned so the number is stated. */
    @Inject(method = "shouldDoEasyPlaceActions()Z", at = @At(value = "RETURN", ordinal = 0))
    private static void notoshi$debugArmed(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onEasyPlaceArmed(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    /**
     * Clears the per-click state before Litematica starts deciding, so the snapshot can never show
     * leftovers from an earlier click.
     *
     * <p>The callback parameter is mandatory even at {@code HEAD}, and because
     * {@code handleEasyPlace} returns {@link InteractionResult} it must be a
     * {@link CallbackInfoReturnable}, not a plain {@code CallbackInfo}: the expected descriptor is
     * {@code (L...CallbackInfoReturnable;)V}, and an omitted parameter does not match. A bad
     * descriptor aborts the whole mixin class, so the cost of getting it wrong is every diagnostic
     * in this file going silent rather than one visible error.</p>
     */
    @Inject(method = HANDLE, at = @At("HEAD"))
    private static void notoshi$attemptStart(CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onEasyPlaceAttempt();
    }

    /**
     * {@code areturn} ordinal 2 — the hard {@code PASS} taken when the schematic cell aimed at is
     * air. It can never {@code FAIL}, but it is pinned all the same so that all nine return sites of
     * {@code handleEasyPlace} are accounted for by name, and so a click that ends here is still
     * counted as a finished click rather than silently vanishing from the counters.
     */
    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 2))
    private static void notoshi$resultNoop(CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onEasyPlaceResult("post-rewrite", 2, cir.getReturnValue());
    }

    /**
     * {@code areturn} ordinal 7 — the {@code SUCCESS} return, taken when {@code useItemOn} itself
     * answered {@code PASS}.
     *
     * <p><b>The two ordinals below are inverted relative to their constant names, so read the
     * bytecode, not the constant.</b> The instructions immediately upstream read:</p>
     *
     * <pre>
     *     754: invokevirtual MultiPlayerGameMode.useItemOn:(...)Lnet/minecraft/world/InteractionResult;
     *     757: astore 29
     *     759: aload 29
     *     761: getstatic InteractionResult.PASS
     *     764: if_acmpne 956            // useItemOn != PASS  -&gt;  jump to the bare PASS return
     *     ...
     *     952: getstatic SUCCESS
     *     955: areturn                  // ordinal 7
     *     956: getstatic PASS
     *     959: areturn                  // ordinal 8
     * </pre>
     *
     * <p>{@code BlockItem#place} on success returns {@code InteractionResult.SUCCESS} (bytecode
     * offset 268 of that method) and on every failure path returns {@code FAIL} — it never returns
     * {@code PASS}. So ordinal 7 means <b>nothing was placed</b>, and ordinal 8 means <b>the click
     * went out</b>; counting a placement from ordinal 7 would count exactly the case that places
     * nothing. See {@link Diagnostics#onUseItemOnResult} for how the real verdict is obtained
     * rather than inferred.
     */
    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 7))
    private static void notoshi$resultNothingPlaced(CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onEasyPlaceResult("post-rewrite", 7, cir.getReturnValue());
    }

    /**
     * {@code areturn} ordinal 8 — the final {@code PASS} return, taken when {@code useItemOn}
     * answered anything other than {@code PASS}, i.e. when Litematica really did issue the click.
     *
     * <p>These two are the only sites a completed click can leave through, which is why they — and
     * not a bare {@code @At("RETURN")} — are where {@code [CLICK]} is logged from.</p>
     */
    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 8))
    private static void notoshi$resultClickIssued(CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onEasyPlaceResult("post-rewrite", 8, cir.getReturnValue());
    }

    // --- branch identification -------------------------------------------------------------------
    //
    // handleEasyPlace has nine areturn sites. Re-derived from Litematica 0.28.8 bytecode:
    //   ordinal 0 -> offset  79  FAIL   placement restriction in effect, trace hit nothing
    //   ordinal 1 -> offset 123  FAIL   placement restriction in effect, target was null
    //   ordinal 2 -> offset 127  PASS   the schematic cell aimed at is air; never FAIL
    //   ordinal 3 -> offset 185  FAIL   block carries a tag Litematica refuses
    //   ordinal 4 -> offset 231  FAIL   !canPlaceBlock / no build item / already correct / too soon
    //   ordinal 5 -> offset 276  FAIL   clickPos == null || hand == null   <- light in mid-air
    //   ordinal 6 -> offset 580  FAIL   placement protocol mustFail (torch/banner/sign/skull)
    //   ordinal 7 -> offset 955  SUCCESS useItemOn answered PASS, i.e. the click did NOTHING
    //   ordinal 8 -> offset 959  PASS    useItemOn answered something else, i.e. the click WENT OUT
    //
    // All nine are hooked. 0, 1, 3, 4, 5 and 6 can FAIL and route through branch(); 2, 7 and 8
    // cannot and route through onEasyPlaceResult(), which is why blocked is counted in branch() and
    // not from the return value of the method.
    //
    // Ordinals 7 and 8 read backwards from their constant names: "ordinal 7 = success" is the
    // natural wrong reading of that bytecode, so notoshi$resultNothingPlaced and
    // notoshi$resultClickIssued say so at the hook.
    //
    // The number passed to branch() is the areturn ordinal verbatim, so it indexes
    // Diagnostics.RETURN_SITES directly and the two cannot drift apart.
    //
    // Note ordinal 5, not 6: the placement protocol never runs for a light block, so ordinal 6 is
    // not the light-block veto someone may expect it to be.

    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 0))
    private static void notoshi$branch0(CallbackInfoReturnable<InteractionResult> cir)
    {
        branch(0, cir);
    }

    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 1))
    private static void notoshi$branch1(CallbackInfoReturnable<InteractionResult> cir)
    {
        branch(1, cir);
    }

    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 3))
    private static void notoshi$branch3(CallbackInfoReturnable<InteractionResult> cir)
    {
        branch(3, cir);
    }

    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 4))
    private static void notoshi$branch4(CallbackInfoReturnable<InteractionResult> cir)
    {
        branch(4, cir);
    }

    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 5))
    private static void notoshi$branch5(CallbackInfoReturnable<InteractionResult> cir)
    {
        branch(5, cir);
    }

    @Inject(method = HANDLE, at = @At(value = "RETURN", ordinal = 6))
    private static void notoshi$branch6(CallbackInfoReturnable<InteractionResult> cir)
    {
        branch(6, cir);
    }

    private static void branch(int ordinal, CallbackInfoReturnable<InteractionResult> cir)
    {
        Diagnostics.onEasyPlaceBranch(ordinal);

        if (cir.getReturnValue() == InteractionResult.FAIL)
        {
            Diagnostics.onEasyPlaceFailed();
        }
    }

    // --- the values the branches test -----------------------------------------------------------

    /**
     * The click position Litematica settled on, or {@code null} when it found none.
     *
     * <p>{@code null} here is decisive: areturn ordinals 0 and 1 both bail out because the target
     * lookup came back empty, and {@code getTargetPosition} only returns {@code null} when the
     * closest thing along the ray was a <b>vanilla</b> block rather than a schematic block — exactly
     * the situation a light block behind/above an opaque block produces.</p>
     */
    @Inject(
            method = "getTargetPosition(Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;)"
                    + "Lnet/minecraft/world/phys/BlockHitResult;",
            at = @At(value = "RETURN", ordinal = 0)
    )
    private static void notoshi$debugTargetNull(CallbackInfoReturnable<BlockHitResult> cir)
    {
        Diagnostics.onTargetPosition(cir.getReturnValue());
    }

    @Inject(
            method = "getTargetPosition(Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;)"
                    + "Lnet/minecraft/world/phys/BlockHitResult;",
            at = @At(value = "RETURN", ordinal = 1)
    )
    private static void notoshi$debugTargetPlacementHandler(CallbackInfoReturnable<BlockHitResult> cir)
    {
        Diagnostics.onTargetPosition(cir.getReturnValue());
    }

    @Inject(
            method = "getTargetPosition(Lfi/dy/masa/litematica/util/RayTraceUtils$RayTraceWrapper;)"
                    + "Lnet/minecraft/world/phys/BlockHitResult;",
            at = @At(value = "RETURN", ordinal = 2)
    )
    private static void notoshi$debugTargetSchematic(CallbackInfoReturnable<BlockHitResult> cir)
    {
        Diagnostics.onTargetPosition(cir.getReturnValue());
    }

    /**
     * The one place Litematica turns "placement restriction is active" into something the player can
     * actually see: {@code handlePlacementRestriction()} prints
     * {@code litematica.message.placement_restriction_fail}.
     *
     * <p>The no-argument {@code placementRestrictionInEffect()} itself is deliberately <b>not</b>
     * observed per return site: it is a static target, so a call-site {@code @Redirect} cannot
     * report its verdict (see the class comment), and its seven {@code ireturn} sites (offsets 30,
     * 57, 132, 153, 185, 235, 237) serve only three call sites. The two {@code handleEasyPlace} call
     * sites, offsets 70 and 108, are each followed immediately by a {@code FAIL} already pinned as
     * {@link #notoshi$branch0} and {@link #notoshi$branch1}, so a {@code true} verdict cannot go
     * unnoticed there. That leaves only this call site, which has exactly one {@code ireturn}
     * (offset 69).</p>
     *
     * <p>No per-return-site counter is therefore needed: {@code blocked} plus {@code lastReturnSite}
     * already distinguish "vetoed by the restriction" from every other veto. This hook adds the one
     * thing those two cannot show — whether the on-screen warning is armed.</p>
     */
    @Inject(method = "handlePlacementRestriction()Z", at = @At(value = "RETURN", ordinal = 0))
    private static void notoshi$debugRestrictionWarningArmed(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onPlacementRestrictionWarning(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    /**
     * Whether Litematica considers the target position replaceable. Light blocks are ordinary full
     * cubes here, so a {@code false} verdict points at something already occupying the cell.
     *
     * <p>Three {@code ireturn} sites in 0.28.8 (offsets 51, 53, 60) for one call site at offset 222
     * of {@code handleEasyPlace}, so all three are pinned. They agree on the verdict — the method
     * checks one thing — and are kept separate only so a future Litematica release that adds an early
     * bail-out shows up in the log instead of silently changing the answer.</p>
     */
    @Inject(
            method = "canPlaceBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At(value = "RETURN", ordinal = 0)
    )
    private static void notoshi$canPlace0(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onCanPlaceBlock(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    @Inject(
            method = "canPlaceBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At(value = "RETURN", ordinal = 1)
    )
    private static void notoshi$canPlace1(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onCanPlaceBlock(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    @Inject(
            method = "canPlaceBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At(value = "RETURN", ordinal = 2)
    )
    private static void notoshi$canPlace2(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onCanPlaceBlock(Boolean.TRUE.equals(cir.getReturnValue()));
    }

    /**
     * The click position Litematica computed for a slab, at {@code getClickPosition}'s first
     * {@code areturn} (offset 18). A light block is never a slab, so this never fires for our case; it
     * is here so both sites of that method are accounted for.
     */
    @Inject(
            method = "getClickPosition(Lnet/minecraft/world/phys/BlockHitResult;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)"
                    + "Lnet/minecraft/world/phys/BlockHitResult;",
            at = @At(value = "RETURN", ordinal = 0)
    )
    private static void notoshi$debugClickPosSlab(CallbackInfoReturnable<BlockHitResult> cir)
    {
        Diagnostics.onClickPosition(cir.getReturnValue());
    }

    /**
     * The exact moment Litematica is about to show the blocking message.
     *
     * <p>Only a note, never a snapshot: {@code handleEasyPlaceWithMessage} returns {@code true} both
     * when it is about to print {@code litematica.message.easy_place_fail} <i>and</i> when the click
     * simply consumed the action, so a snapshot here would fire on successful placements too.
     * {@link Diagnostics#onEasyPlaceFailed()} owns the snapshot.</p>
     *
     * <p>Three {@code ireturn} sites in 0.28.8 (offsets 7, 86, 103), all three pinned: they mean
     * "click consumed and the fail message is being printed", "click consumed, no message",
     * "not handled at all".</p>
     */
    @Inject(method = "handleEasyPlaceWithMessage()Z", at = @At(value = "RETURN", ordinal = 0))
    private static void notoshi$debugMessageShown(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onEasyPlaceWithMessage(
                Boolean.TRUE.equals(cir.getReturnValue())
                        ? Diagnostics.LEGACY_MESSAGE_SHOWN
                        : Diagnostics.LEGACY_MESSAGE_NOT_SHOWN);
    }

    @Inject(method = "handleEasyPlaceWithMessage()Z", at = @At(value = "RETURN", ordinal = 1))
    private static void notoshi$debugMessageSilent(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onEasyPlaceWithMessage(
                Boolean.TRUE.equals(cir.getReturnValue())
                        ? Diagnostics.LEGACY_MESSAGE_CONSUMED
                        : Diagnostics.LEGACY_MESSAGE_NOT_CONSUMED);
    }

    @Inject(method = "handleEasyPlaceWithMessage()Z", at = @At(value = "RETURN", ordinal = 2))
    private static void notoshi$debugMessageUnhandled(CallbackInfoReturnable<Boolean> cir)
    {
        Diagnostics.onEasyPlaceWithMessage(
                Boolean.TRUE.equals(cir.getReturnValue())
                        ? Diagnostics.LEGACY_MESSAGE_CONSUMED
                        : Diagnostics.LEGACY_MESSAGE_NOT_CONSUMED);
    }
}