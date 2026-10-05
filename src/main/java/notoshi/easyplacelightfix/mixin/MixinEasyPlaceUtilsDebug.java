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
 * There is no bare {@code @At("RETURN")} anywhere in this class, and that is a rule, not a
 * coincidence — three separate bugs in 1.0–1.2 all came from leaning on the default:
 *
 * <ul>
 *   <li>1.2.0's result hook fired at the same instruction as {@link #notoshi$branch0}, so it
 *       reported {@code FAIL} with no branch attributed ({@code return site: <none recorded>}) and
 *       then printed a banner blaming another mod for a method that was never touched;</li>
 *   <li>{@code notoshi$debugTarget} observed one of {@code getTargetPosition}'s four returns, so
 *       the snapshot alternated between a real position and
 *       {@code <target lookup not reached>} on clicks where the lookup demonstrably had run
 *       ({@code [RESCUED]} was printed at the very same timestamp);</li>
 *   <li>{@code notoshi$debugRestriction} and {@code notoshi$debugCanPlace} observed one of seven
 *       and one of three returns respectively, which is why {@code restrictionTrue} read 7 against
 *       {@code blocked = 41}.</li>
 * </ul>
 *
 * <h3>There is no {@code @Redirect} in this class, and that is not a style choice</h3>
 * 1.3.0 tried to observe {@code placementRestrictionInEffect()} and {@code canPlaceBlock()} by
 * redirecting their <i>call sites</i> instead of pinning seven and three returns each, on the
 * reasoning that a redirect sees every verdict by construction. Both are {@code static} methods, and
 * a {@code static} target is the one case where that reasoning fails: Mixin builds a redirect
 * handler's signature from the target's parameters and its <b>return type</b>, and does <b>not</b>
 * pass the return value in as a parameter. So there is no way to both observe the verdict and keep
 * the original behaviour — calling the method from inside the handler re-enters the redirect.
 *
 * <p>Two live errors from the 1.3.0 tester log, verbatim, because both signatures look reasonable:
 * </p>
 *
 * <pre>
 *     notoshi$debugRestrictionFirst(Z)Z
 *       Found 1 unexpected additional method arguments: (boolean)
 *     notoshi$legacyResult(Minecraft, InteractionResult)
 *       Found unexpected argument type net.minecraft.world.InteractionResult at index 1,
 *       expected net.minecraft.client.Minecraft
 *       Expected signature: (Lnet/minecraft/client/Minecraft;
 *                              Lnet/minecraft/client/Minecraft;)Lnet/minecraft/world/InteractionResult;
 * </pre>
 *
 * <p>A rejected handler descriptor aborts the whole mixin class, so all three cost this mod every
 * diagnostic in this file, not just the three handlers — and because this config is
 * {@code required: false} the only symptom was one {@code WARN} line. The value of a helper is
 * therefore observed the boring way: one pinned {@code @Inject} per return site.</p>
 *
 * <p>Where a helper's verdict is not needed it is simply not observed. {@code placementRestrictionInEffect()}
 * has three call sites — {@code handleEasyPlace} offsets 70 and 108, which are immediately followed
 * by the {@code FAIL} returns already pinned as ordinals 0 and 1, and {@code handlePlacementRestriction},
 * which is pinned here once. A {@code true} verdict therefore cannot hide anywhere, so there is no
 * counter for it.</p>
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
     * {@link CallbackInfoReturnable}, not a plain {@code CallbackInfo}. Leaving it out failed with
     * {@code Expected (L...CallbackInfoReturnable;)V but found ()V} and, because a bad descriptor
     * aborts the whole mixin class, took all diagnostics below down with it — the failure looked
     * like "the mod is quiet", not like an error.</p>
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
     * <p><b>This is the trap that made 1.0–1.2 print {@code placed=0} forever.</b> The bytecode
     * immediately upstream of it reads:</p>
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
     * <p>and {@code BlockItem#place} on success returns {@code InteractionResult.SUCCESS}
     * (bytecode offset 268 of that method) and on every failure path returns {@code FAIL} — it never
     * returns {@code PASS}. So ordinal 7 means <b>useItemOn did nothing</b>, and ordinal 8 means
     * <b>the block is down</b>. 1.0–1.3.0 counted {@code placed++} on ordinal 7, i.e. exactly in the
     * one case that places nothing, which is why a session with 19 successful placements reported
     * {@code placed=0 blocked=180 passed=47}. The counter is fixed in 1.3.1; see
     * {@link Diagnostics#onUseItemOnResult} for how the real verdict is obtained rather than inferred.
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
    // Ordinals 7 and 8 are the pair 1.0-1.3.0 read backwards; notoshi$resultNothingPlaced and
    // notoshi$resultClickIssued say so at the hook, because "ordinal 7 = success" is the most
    // natural wrong reading of that bytecode there is.
    //
    // The number passed to branch() is the areturn ordinal verbatim, so it indexes
    // Diagnostics.RETURN_SITES directly and the two cannot drift apart.
    //
    // Note ordinal 5, not 6: the earlier labelling called this "6/6 the placement protocol vetoed
    // this block", which sent the diagnosis in exactly the wrong direction - the placement protocol
    // never even runs for a light block.

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
     * observed per return site. It has seven {@code ireturn} sites (offsets 30, 57, 132, 153, 185,
     * 235, 237) and three call sites — {@code handleEasyPlace} offsets 70 and 108, and this method —
     * and 1.3.0's attempt to watch those call sites instead is what took this entire mixin class
     * down (see the class comment). The two {@code handleEasyPlace} call sites are each followed
     * immediately by a {@code FAIL} that is already pinned as {@link #notoshi$branch0} and
     * {@link #notoshi$branch1}, so a {@code true} verdict cannot go unnoticed there. That leaves only
     * this call site, which has exactly one {@code ireturn} (offset 69).</p>
     *
     * <p>So the counter {@code restrictionTrue}, which read 0 for an entire tester session because
     * this class never applied, is gone rather than reintroduced: {@code blocked} plus
     * {@code lastReturnSite} already distinguish "vetoed by the restriction" from every other veto.
     * This hook adds the one thing those two cannot show — whether the on-screen warning is armed.</p>
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
     *
     * <p>1.3.0 redirected the single call site instead and the handler was rejected
     * ({@code Found 1 unexpected additional method arguments: (boolean)}), which aborted this whole
     * class. See the class comment for why a redirect cannot work here.</p>
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
     * "not handled at all". 1.0–1.2 only ever saw one of them.</p>
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