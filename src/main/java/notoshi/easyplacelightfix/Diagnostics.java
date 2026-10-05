package notoshi.easyplacelightfix;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * All diagnostics live here: counters, throttling, a dedicated log file, and the "why did it not
 * work" snapshot that is dumped every time Litematica blocks an Easy Place click.
 *
 * <p>The goal is to be able to answer three questions from the log alone:</p>
 * <ol>
 *   <li><b>Did the fix actually fire?</b> &mdash; the {@code [FIX-ACTIVE]} line, printed the first
 *       time the redirect rewrites a light block's shape.</li>
 *   <li><b>Which Easy Place path is live?</b> &mdash; {@code [CLICK]} lines are tagged
 *       {@code legacy} or {@code post-rewrite}.</li>
 *   <li><b>Why was the click rejected?</b> &mdash; the {@code [BLOCKED]} snapshot, whose
 *       {@code reject branch} line names the exact bail-out.</li>
 * </ol>
 */
public final class Diagnostics
{
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // --- counters --------------------------------------------------------------------------------

    /** Times the schematic ray trace asked a light block for its shape. */
    public static int lightBlockQueries;

    public static int legacyCalls;
    public static int postRewriteCalls;

    /**
     * Clicks that really put a block down: Easy Place left through a return site that means "the click
     * went out" <b>and</b> the last {@code useItemOn} answered {@link InteractionResult#SUCCESS}.
     *
     * <p>Both conditions are real observations. Note that the areturn ordinal alone is not enough —
     * see {@link #RETURN_SITES} on why ordinals 7 and 8 read the opposite of what their names
     * suggest.</p>
     */
    public static int placed;

    /**
     * Clicks that went out and vanilla refused: a "the click went out" return site combined with the
     * last {@code useItemOn} answering {@code FAIL}, {@code CONSUME} or {@code null}.
     */
    public static int clickRefused;

    public static int blocked;

    /**
     * Clicks that reached a return site without being a rejection and without placing anything:
     * areturn ordinal 2 (aimed at schematic air) and ordinal 7 ({@code useItemOn} answered
     * {@code PASS}). Neither is a failure.
     */
    public static int passed;

    /**
     * Times Litematica's on-screen "action blocked" warning was armed, i.e. how often
     * {@code EasyPlaceUtils#handlePlacementRestriction()} answered {@code true}.
     *
     * <p>Observed at that method's single {@code ireturn} rather than at the seven call sites of
     * {@code placementRestrictionInEffect()}: one hook instead of seven, and it watches the one place
     * where Litematica actually acts on the boolean. Note that {@code blocked} plus
     * {@link #lastReturnSite} already cover the same ground from the other side — areturn ordinals 0
     * and 1 <i>are</i> the placement-restriction vetoes.</p>
     */
    public static int restrictionWarningArmed;

    public static BlockPos lastLightPos;
    public static String lastLightState = "<never>";

    public static BlockPos lastVanillaHitPos;
    public static String lastVanillaHitInfo = "<none>";

    // --- per-click state of the rewritten Easy Place path ----------------------------------------

    /** Which {@code areturn} site rejected the click, together with the verdict that site tests. */
    private static String lastFailBranch = "<no click rejected yet>";

    /** Which {@code areturn} of {@code EasyPlaceUtils#handleEasyPlace} the click ended on, if known. */
    private static String lastReturnSite = "<no click recorded yet>";

    /** What {@code handleEasyPlaceWithMessage} did with the click's verdict. */
    private static String lastWithMessage = "<not consulted>";

    /** Where Easy Place was going to click. {@code null} means the target lookup came back empty. */
    private static BlockPos lastEasyPlaceTargetPos;
    private static String lastTargetInfo = "<target lookup not reached>";

    /**
     * Whether Litematica's on-screen "action blocked" warning was armed for the click in progress.
     * Fed by {@link #onPlacementRestrictionWarning}.
     */
    private static String lastRestrictionInfo = "<restriction check not reached>";

    /**
     * What {@code MultiPlayerGameMode#useItemOn} answered for the click in progress, and whether that
     * answer means the block is down.
     *
     * <p>This is the field that makes {@link #placed} trustworthy. Litematica's own return value
     * cannot: {@code handleEasyPlace} returns {@code PASS} on a successful placement and
     * {@code SUCCESS} on a no-op.</p>
     */
    private static String lastClickAnswer = "<no click reached useItemOn>";

    private static boolean lastClickPlaced;

    /** Verdict of {@code canPlaceBlock()}, which only branch 4 consults. */
    private static String lastCanPlaceBlockInfo = "<canPlaceBlock not consulted>";

    /** Result of {@code getClickPosition()}, which only the surviving clicks reach. */
    private static String lastClickPosInfo = "<click position not computed>";

    /** Times {@code getAdjacentClickPosition} was answered with a direct click on the target cell. */
    public static int directLightClicks;

    private static String lastDirectClickInfo = "<direct click not attempted>";
    private static long lastDirectLogMs;

    /**
     * Whether a {@code [BLOCKED]} snapshot has already been dumped for the click in progress. Guards
     * against the three call sites that could each fire for one rejection, which would otherwise print
     * three near-identical copies of the same verdict.
     */
    private static boolean snapshotTaken;

    /**
     * Whether any of {@code doEasyPlaceAction}'s eleven pinned {@code areturn} sites actually ran
     * during the click in progress. Reset by {@link #onLegacyAttempt}.
     *
     * <p>This is what distinguishes "Litematica's own code rejected the click" from "some other mod
     * replaced the method". {@code WorldUtils#handleEasyPlace} reads {@code doEasyPlaceAction}'s
     * answer and, on {@code FAIL}, prints the Easy Place failure message and returns {@code true} —
     * it cannot tell where that answer came from. So if the message appears while this flag is still
     * {@code false}, the {@code FAIL} did not come from any of the eleven returns we observe, which
     * means a mod cancelled the method ({@code EasyPlaceFix} injects at the {@code getHitType()}
     * call, offset 79) or Litematica's method shape changed.</p>
     */
    private static boolean legacyReturnSeen;

    /** Set once by {@link #onLegacyResult}, and printed when the flag above is still false. */
    private static String legacyForeignAnswer = "<not consulted>";

    /**
     * What each of {@code handleEasyPlace}'s nine {@code areturn} sites returns, in bytecode order.
     * Kept next to the {@code @At(value = "RETURN", ordinal = n)} hooks in
     * {@code MixinEasyPlaceUtilsDebug}, which index straight into this array — so the index in the
     * mixin <em>is</em> the areturn ordinal and the two can never drift apart.
     *
     * <p>Only six of the nine can return {@link InteractionResult#FAIL}. Ordinal 2 is a hard
     * {@code PASS} and 7/8 are {@code SUCCESS}/{@code PASS}; they are listed anyway so an ordinal is
     * never out of range and a shifted ordinal produces a wrong-but-readable answer instead of
     * nothing at all.</p>
     *
     * <p><b>Ordinals 7 and 8 mean the opposite of what they look like.</b> The bytecode at the end
     * of the method is:</p>
     *
     * <pre>
     *     754: invokevirtual MultiPlayerGameMode.useItemOn:(...)Lnet/minecraft/world/InteractionResult;
     *     757: astore 29
     *     759: aload 29
     *     761: getstatic InteractionResult.PASS
     *     764: if_acmpne 956            // useItemOn != PASS -&gt; bare PASS return
     *     ...
     *     952: getstatic SUCCESS
     *     955: areturn                  // ordinal 7
     *     956: getstatic PASS
     *     959: areturn                  // ordinal 8
     * </pre>
     *
     * <p>and {@code BlockItem#place} returns {@code InteractionResult.SUCCESS} when it places a block
     * (its bytecode offset 268) and {@code FAIL} on every one of its five failure paths — it never
     * returns {@code PASS} at all. So:</p>
     *
     * <ul>
     *   <li>ordinal 7 ({@code SUCCESS}) means {@code useItemOn} answered {@code PASS} means
     *       <b>nothing was placed</b>;</li>
     *   <li>ordinal 8 ({@code PASS}) means the click really went out, and whether the block came down
     *       depends on whether {@code useItemOn} answered {@code SUCCESS}. That answer is read
     *       directly off {@code useItemOn} itself — see {@link #onUseItemOnResult}.</li>
     * </ul>
     *
     * <p>Offsets from Litematica 0.28.8 bytecode. The one that matters in practice is <b>5</b>:
     * {@code clickPos == null || hand == null}, which is where a light block in mid-air dies.</p>
     */
    static final String[] RETURN_SITES = {
        /* 0 */ "ordinal 0 / offset 79  - FAIL: no schematic target on the ray AND the placement"
                + " restriction is active. This is the NORMAL, HARMLESS outcome of the click after a"
                + " light block went down: the cell is no longer a missing block, so the next attempt"
                + " of the same button hold lands here. Not a failure of this mod.",
        /* 1 */ "ordinal 1 / offset 123 - FAIL: getTargetPosition() returned null because a closer"
                + " VANILLA block won the ray. This is exactly what rescueLightTarget prevents, so"
                + " seeing it means the rescue did not fire.",
        /* 2 */ "ordinal 2 / offset 127 - PASS: the schematic cell aimed at is air. Never FAIL.",
        /* 3 */ "ordinal 3 / offset 185 - FAIL: the block carries a tag Litematica refuses to"
                + " easy-place.",
        /* 4 */ "ordinal 4 / offset 231 - FAIL: canPlaceBlock() says no, or there is no build item,"
                + " or the cell is already correct, or the last click was too recent.",
        /* 5 */ "ordinal 5 / offset 276 - FAIL: getClickPosition() returned null (no block to click)"
                + " OR the build item ended up in neither hand. This is where a mid-air light block"
                + " used to die; directClickForLightBlocks is the fix, so seeing it means that"
                + " substitution did not fire.",
        /* 6 */ "ordinal 6 / offset 580 - FAIL: the placement protocol vetoed this block"
                + " (torch / banner / sign / skull only).",
        /* 7 */ "ordinal 7 / offset 955 - SUCCESS, and it means NOTHING WAS PLACED: it is taken when"
                + " useItemOn answered PASS. See the class comment - this pair is inverted relative to"
                + " its own names.",
        /* 8 */ "ordinal 8 / offset 959 - PASS, and it means THE CLICK WENT OUT: taken when useItemOn"
                + " answered anything but PASS. Read 'vanilla answer to the click' below for whether"
                + " the block actually came down.",
    };

    /**
     * What each of {@code WorldUtils#doEasyPlaceAction}'s eleven {@code areturn} sites returns, in
     * bytecode order. Indexed by the {@code ordinal} of the matching
     * {@code @At(value = "RETURN", ordinal = n)} hook in {@code MixinWorldUtilsDebug}.
     *
     * <p>Every offset is from Litematica 0.28.8 {@code WorldUtils#doEasyPlaceAction}. Seven of the
     * eleven can return {@link InteractionResult#FAIL}; the rest are listed anyway so an ordinal is
     * never out of range.</p>
     *
     * <p>Pinning all eleven is what makes a legacy-path rejection name the site that produced it.
     * Without them the snapshot can only say "doEasyPlaceAction answered FAIL", which is the same
     * string for a benign already-filled cell and for a genuine failure.</p>
     */
    static final String[] LEGACY_RETURN_SITES = {
        /* 0 */ "ordinal 0 / offset 69  - FAIL: the schematic trace found nothing AND"
                + " placementRestrictionInEffect(mc) is true. The NORMAL outcome of the next click of a"
                + " hold once the cell is no longer a missing block.",
        /* 1 */ "ordinal 1 / offset 77  - PASS: the schematic trace found nothing and no placement"
                + " restriction applies. Never FAIL.",
        /* 2 */ "ordinal 2 / offset 155 - FAIL: easyPlaceIsPositionCached(pos) - this exact cell was"
                + " already done during this button hold. Expected while holding the button over a"
                + " partly filled area.",
        /* 3 */ "ordinal 3 / offset 165 - FAIL: easyPlaceIsTooFast() - Litematica's item-swap rate"
                + " limiter: (now - lastPickBlockTime) / 1e6 must be at least EASY_PLACE_SWAP_INTERVAL"
                + " ms. That timestamp is only touched when the build item actually changed hands.",
        /* 4 */ "ordinal 4 / offset 195 - FAIL: schematicState == clientState - the cell ALREADY holds"
                + " exactly the right block. Harmless; it means that cell is done.",
        /* 5 */ "ordinal 5 / offset 217 - FAIL: easyPlaceBlockChecksCancel(schematic, client, player,"
                + " vanillaHit, item) answered true. It answers FALSE when vanillaHit is a MISS (offset"
                + " 77 of that method), so aiming at mid-air cannot land here - only a vanilla BLOCK"
                + " hit on a block that is not replaceable can.",
        /* 6 */ "ordinal 6 / offset 247 - FAIL: getUsedHandForItem() returned null - after"
                + " schematicWorldPickBlock() put the build item in a hand, neither hand holds it. A"
                + " stable loop here means the light block never reaches a hand at all.",
        /* 7 */ "ordinal 7 / offset 432 - FAIL: applyPlacementProtocolAll() said mustFail"
                + " (torch / banner / sign / skull only).",
        /* 8 */ "ordinal 8 / offset 703 - SUCCESS, reached either after the click went out at offset"
                + " 558 or because the build item was empty (offset 171 jumps straight here). Read"
                + " 'vanilla answer to the click' to tell those two apart.",
        /* 9 */ "ordinal 9 / offset 730 - FAIL or PASS: the trace hit a VANILLA_BLOCK rather than a"
                + " schematic block - FAIL with the placement restriction active, PASS without it.",
        /* 10 */ "ordinal 10 / offset 734 - PASS: the trace hit neither a schematic nor a vanilla"
                + " block. Never FAIL.",
    };

    private static Boolean lastArmed;
    private static String lastPath = "<none>";
    private static long lastArmedLogMs;

    private static long lastTraceLogMs;
    private static long lastSnapshotMs;
    private static long lastClickLogMs;
    private static int snapshotsWritten;

    private static boolean fileWritable = true;
    private static boolean filePrepared;

    private Diagnostics() {}

    // --- public API ------------------------------------------------------------------------------

    /**
     * Called from the redirect handler whenever Litemica's schematic ray trace asks a block for its
     * shape and that block is a light block. This is the proof that the mixin is alive and that the
     * light block is now visible to the trace.
     */
    public static void onLightBlockShapeQuery(BlockPos pos, BlockState state, VoxelShape vanillaShape)
    {
        lightBlockQueries++;
        lastLightPos = pos.immutable();
        lastLightState = describe(state);

        if (!ModConfig.get().debug)
        {
            return;
        }

        boolean first = lightBlockQueries == 1;
        long now = System.currentTimeMillis();

        if (!first && now - lastTraceLogMs < ModConfig.get().traceThrottleMs)
        {
            return;
        }

        lastTraceLogMs = now;

        if (first)
        {
            log("FIX-ACTIVE",
                    "redirect fired for minecraft:light at " + fmt(pos)
                    + " -> the mixin IS applied and Litematica's schematic ray trace can now see it");
        }

        log("TRACE", "light block at " + fmt(pos) + " " + lastLightState
                + "  vanilla shape=" + shapeInfo(vanillaShape)
                + " -> forced FULL_CUBE"
                + "  [lightQueries=" + lightBlockQueries + "]");
    }

    /**
     * Records {@code EasyPlaceUtils#shouldDoEasyPlaceActions()} — whether Easy Place is armed at all
     * (config on, tool mode correct, activation key held).
     */
    public static void onEasyPlaceArmed(boolean armed)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        Boolean previous = lastArmed;

        if (Boolean.valueOf(armed).equals(previous))
        {
            return;
        }

        long now = System.currentTimeMillis();

        // Easy Place is asked "are you armed?" several times per click, so a held button flaps this
        // line for reasons that have nothing to do with this mod. Report each transition at most
        // once per second; the value itself is already in every snapshot.
        if (previous != null && now - lastArmedLogMs < 1000L)
        {
            return;
        }

        lastArmedLogMs = now;
        lastArmed = armed;

        log("ARMED", armed
                ? "Litematica's own gate says Easy Place may act right now."
                : "Litematica's own gate says Easy Place must not act right now. That gate is"
                        + " easyPlaceMode + not the REBUILD tool mode + the activation key held; it is"
                        + " evaluated by Litematica, not by this mod, and it flips back the moment you"
                        + " press the activation key again.");
    }

    /**
     * Notes that Litematica's legacy hold-to-place tick path is being evaluated. Runs every tick while
     * the activation key is held, so it is logged once per transition.
     */
    public static void onLegacyTickPath()
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        if (lastPath.equals("legacy"))
        {
            return;
        }

        setLegacyPathDefaults();

        log("ARMED", "legacy hold-to-place tick path is active (easyPlacePostRewrite OFF = default)");
    }

    /**
     * The legacy path was reached, per click.
     *
     * <p>Resets the per-click state exactly as {@link #onEasyPlaceAttempt()} does for the rewritten
     * path, so the two paths never share fields. Without that, switching the Litematica option
     * mid-session produces a snapshot whose {@code areturn site} describes
     * {@code EasyPlaceUtils#handleEasyPlace} while {@code doEasyPlaceAction} is the method actually
     * running.</p>
     */
    public static void onLegacyAttempt(Minecraft mc)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        lastPath = "legacy";

        resetPerClick();

        // All eleven areturn sites are pinned, so this field starts empty and is filled by whichever
        // site actually ran.
        lastReturnSite = "<no return site recorded yet>";
        lastTargetInfo = "<legacy path resolves its own target>";
        lastClickPosInfo = "<legacy path clicks the target cell itself, see offset 530>";
        lastDirectClickInfo = "<legacy path clicks the target cell natively>";
        lastCanPlaceBlockInfo = "<legacy path does not call canPlaceBlock>";

        legacyReturnSeen = false;

        captureVanillaHit(mc);
    }

    private static void setLegacyPathDefaults()
    {
        lastPath = "legacy";
        lastReturnSite = "<no return site recorded yet>";
        lastFailBranch = "<no click rejected>";
        lastWithMessage = "<not consulted>";
        lastTargetInfo = "<legacy path resolves its own target>";
        lastRestrictionInfo = "<legacy path: Litematica's own veto, observed only on screen>";
        lastDirectClickInfo = "<legacy path clicks the target cell natively>";
        lastCanPlaceBlockInfo = "<legacy path does not call canPlaceBlock>";
    }

    private static void resetPerClick()
    {
        lastFailBranch = "<no click rejected>";
        lastReturnSite = "<no click recorded>";
        lastWithMessage = "<not consulted>";
        snapshotTaken = false;
        lastDirectClickInfo = "<direct click not attempted>";
        lastEasyPlaceTargetPos = null;
        lastTargetInfo = "<target lookup not reached>";
        lastRestrictionInfo = "<restriction check not reached>";
        lastCanPlaceBlockInfo = "<canPlaceBlock not consulted>";
        lastClickPosInfo = "<click position not computed>";
        lastClickAnswer = "<no click reached useItemOn>";
        lastClickPlaced = false;
    }

    /**
     * Resets the per-click state at {@code EasyPlaceUtils#handleEasyPlace} entry, so a snapshot can
     * never mix leftovers from an earlier click into the verdict of the current one.
     */
    public static void onEasyPlaceAttempt()
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        resetPerClick();
    }

    /**
     * Records which {@code areturn} site of {@code handleEasyPlace} rejected the click. Six of the
     * nine sites can return {@link InteractionResult#FAIL}, so this is the only thing that turns "it
     * said FAIL" into "it said FAIL <i>because</i>".
     *
     * @param ordinal the {@code areturn} ordinal, passed straight through from the
     *                {@code @At(value = "RETURN", ordinal = ...)} hook so it indexes
     *                {@link #RETURN_SITES} directly
     */
    public static void onEasyPlaceBranch(int ordinal)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        lastReturnSite = ordinal >= 0 && ordinal < RETURN_SITES.length
                ? RETURN_SITES[ordinal]
                : "<unknown areturn ordinal " + ordinal + " - Litematica changed handleEasyPlace()>";
    }

    /**
     * Called by each branch hook whose site actually returned {@link InteractionResult#FAIL}, so the
     * verdict is attributed to a real observation rather than to whichever site happened to run last.
     */
    public static void onEasyPlaceFailed()
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        lastFailBranch = lastReturnSite;
        blocked++;

        onBlocked(null, Minecraft.getInstance());
    }

    /**
     * Notes for the three {@code ireturn} sites of {@code handleEasyPlaceWithMessage}, each stated by
     * name rather than collapsed into a boolean.
     *
     * <p>The method returns {@code true} both when it is about to print
     * {@code litematica.message.easy_place_fail} and when the click merely consumed the action, and
     * {@code false} both for "consumed, nothing printed" and for "not handled at all" — only the
     * caller's own return value distinguishes those.</p>
     */
    public static final String LEGACY_MESSAGE_SHOWN =
            "the fail message is being printed right now (litematica.message.easy_place_fail)";

    public static final String LEGACY_MESSAGE_CONSUMED = "action consumed, nothing printed";

    public static final String LEGACY_MESSAGE_NOT_SHOWN =
            "nothing printed and the action was not consumed - Easy Place declined the click";

    public static final String LEGACY_MESSAGE_NOT_CONSUMED = "nothing happened at all";

    /**
     * Records what {@code handleEasyPlaceWithMessage} did with the click.
     *
     * @param note one of the {@code LEGACY_MESSAGE_*} constants, so the snapshot names the exact
     *             {@code ireturn} site instead of a boolean that two sites share
     */
    public static void onEasyPlaceWithMessage(String note)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        lastWithMessage = note;
    }

    /**
     * Counts {@code FAIL} verdicts that no {@code areturn} hook could attribute to a site.
     *
     * <p>Expected to stay 0: all nine {@code handleEasyPlace} sites and all eleven
     * {@code doEasyPlaceAction} sites are pinned by an explicit ordinal, so an unattributed {@code FAIL}
     * means one of those methods was rewritten by another mod. Treated as a tripwire rather than a
     * routine report, which is why it is only counted and only surfaced in the counter line.</p>
     */
    public static int unattributedFails;
    public static void onEasyPlaceUnattributedFail()
    {
        unattributedFails++;
    }

    /** Records the position Easy Place resolved, or {@code null} if it found none at all. */
    public static void onTargetPosition(BlockHitResult hit)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        if (hit == null)
        {
            lastEasyPlaceTargetPos = null;
            lastTargetInfo = "<null> - Litematica found no position to click at all";

            return;
        }

        BlockPos pos = hit.getBlockPos();

        lastEasyPlaceTargetPos = pos.immutable();
        lastTargetInfo = fmt(pos) + " face=" + hit.getDirection();
    }

    /**
     * Records Litematica's on-screen placement-restriction verdict, read from the one
     * {@code ireturn} of {@code EasyPlaceUtils#handlePlacementRestriction()} — the only place
     * Litematica acts on that boolean.
     */
    public static void onPlacementRestrictionWarning(boolean armed)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        if (armed)
        {
            restrictionWarningArmed++;
        }

        lastRestrictionInfo = armed
                ? "ARMED - Litematica considers the click restricted and says so on screen"
                : "not armed - Litematica's own veto is off";
    }

    /** Records the {@code canPlaceBlock} verdict consulted by branch 4. */
    public static void onCanPlaceBlock(boolean canPlace)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        lastCanPlaceBlockInfo = canPlace
                ? "true - Litematica accepts the target position as replaceable"
                : "FALSE - Litematica says the target position is not replaceable";
    }

    /** Records the {@code getClickPosition} result; {@code null} is one half of branch 5. */
    public static void onClickPosition(BlockHitResult hit)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        lastClickPosInfo = hit == null
                ? "<null> - no click position could be computed (other half of areturn ordinal 5: the"
                        + " item is in neither hand)"
                : fmt(hit.getBlockPos()) + " face=" + hit.getDirection();
    }

    /**
     * Records what {@code MultiPlayerGameMode#useItemOn} answered for the click in progress.
     *
     * <p>Called from both of {@code useItemOn}'s {@code areturn} sites (bytecode offsets 27 and 67).
     * Overwrites rather than accumulates, because for a light block that method is entered twice per
     * click and only the last answer describes the Easy Place placement.</p>
     *
     * <p>This exists because Litematica reports the opposite of what its own return value means — see
     * {@link #RETURN_SITES} ordinals 7 and 8. Without this, a log cannot distinguish "the block is
     * down" from "vanilla refused", which is the single most important thing to know about a click
     * that did not visibly appear.</p>
     *
     * @param verdict human-readable form, built in {@code PrinterDelivery#onUseItemOnResult} so the
     *                {@link InteractionResult} knowledge stays in one place
     * @param succeeded whether the answer was {@link InteractionResult#SUCCESS}, which for a
     *                   {@code BlockItem} means the block is down
     */
    public static void onUseItemOnResult(String verdict, boolean succeeded)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        lastClickAnswer = verdict;
        lastClickPlaced = succeeded;
    }

    /**
     * Records the outcome of a click that ran all the way through {@code EasyPlaceUtils#handleEasyPlace}.
     *
     * <p>Reached only from areturn ordinals 2, 7 and 8 — the three returns a click that was not
     * rejected can leave by. The verdict is <b>not</b> read from the returned {@link InteractionResult}:
     * ordinal 8 is {@code PASS} precisely when the block went down, and ordinal 7 is
     * {@code SUCCESS} precisely when nothing did. What happened is decided by
     * {@link #lastClickAnswer}, which is {@code useItemOn}'s own answer, read in
     * {@code PrinterDelivery#onUseItemOnResult}.</p>
     *
     * @param ordinal the {@code areturn} ordinal, passed straight through from the mixin so it
     *                indexes {@link #RETURN_SITES} directly
     */
    public static void onEasyPlaceResult(String path, int ordinal, InteractionResult result)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        if (path.startsWith("legacy"))
        {
            legacyCalls++;
        }
        else
        {
            postRewriteCalls++;
        }

        lastPath = path;
        lastReturnSite = RETURN_SITES[ordinal];

        // lastClickPlaced is reset at click START (onEasyPlaceAttempt / onLegacyAttempt), not here:
        // useItemOn already answered by now, and clearing it here would wipe the verdict read below.
        String name = result == null ? "<null>" : String.valueOf(result);

        if (result == InteractionResult.FAIL)
        {
            // Defensive only. Every completing site is pinned explicitly, so this branch should be
            // unreachable; counted rather than guessed at, so the summary can prove it.
            blocked++;

            // All nine sites are pinned by an explicit ordinal, so an unrecorded one can only mean
            // handleEasyPlace itself was rewritten.
            if (lastReturnSite.startsWith("<"))
            {
                onEasyPlaceUnattributedFail();
            }
        }
        else if (ordinal == 8)
        {
            // The click went out. Whether the block is down is useItemOn's answer, not this one's.
            if (lastClickPlaced)
            {
                placed++;
            }
            else
            {
                clickRefused++;
            }
        }
        else
        {
            // Ordinals 2 and 7: aimed at air, or useItemOn answered PASS. Nothing to place, nothing
            // broken.
            passed++;
        }

        long now = System.currentTimeMillis();

        // The first completed click is always logged (that is the interesting case), as is any click
        // whose outcome was not a plain success; the rest is throttled, because a held right mouse
        // button evaluates this every single tick.
        boolean interesting = ordinal == 8 && !lastClickPlaced;

        if (postRewriteCalls + legacyCalls == 1 || interesting
                || now - lastClickLogMs >= ModConfig.get().traceThrottleMs)
        {
            lastClickLogMs = now;

            log("CLICK", path + " -> handleEasyPlace returned " + name
                    + " (areturn ordinal " + ordinal + ")"
                    + "  |  " + answer()
                    + "  [placed=" + placed + " refused=" + clickRefused
                    + " blocked=" + blocked + " passed=" + passed + "]");
        }
    }

    /**
     * Records the outcome of one {@code WorldUtils#doEasyPlaceAction} call, from whichever of its
     * eleven {@code areturn} sites left it.
     *
     * <p>Called from all eleven pinned sites, so the legacy path's counters move and its rejections
     * name a site. A path whose rejection the log cannot explain is not diagnosed, whether or not it
     * needs a click fix.</p>
     *
     * <p>The verdict is {@link #lastClickPlaced}, not this method's own {@link InteractionResult}:
     * offset 703 answers {@code SUCCESS} both after a click that did nothing and when the build item
     * was empty. The {@code useItemOn} hooks sit on {@code MultiPlayerGameMode} and so are
     * path-agnostic — their answer is available on this path too.</p>
     *
     * @param ordinal the {@code areturn} ordinal, passed straight through from the mixin so it
     *                indexes {@link #LEGACY_RETURN_SITES} directly
     * @param result  the value the site returned, as read from the {@code CallbackInfoReturnable}
     */
    public static void onLegacyResult(int ordinal, InteractionResult result)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        legacyReturnSeen = true;
        legacyCalls++;

        lastReturnSite = ordinal >= 0 && ordinal < LEGACY_RETURN_SITES.length
                ? LEGACY_RETURN_SITES[ordinal]
                : "<unknown doEasyPlaceAction areturn ordinal " + ordinal
                        + " - Litematica changed the method; the table below is stale>";

        String name = result == null ? "<null>" : String.valueOf(result);

        if (result == InteractionResult.FAIL)
        {
            blocked++;
            lastFailBranch = lastReturnSite;

            // Writing the snapshot here rather than at handleEasyPlace's return keeps the branch,
            // the counter and the snapshot on one observation, so they cannot disagree.
            onBlocked(null, Minecraft.getInstance());

            return;
        }

        // PASS and SUCCESS alike mean nothing was vetoed. Which of the two is which does not
        // matter for the counters, but ordinal 8 is the one that follows a click, so it is the only
        // site at which "the click went out and this is what vanilla said" is a real answer.
        if (ordinal == 8)
        {
            if (lastClickPlaced)
            {
                placed++;
            }
            else if (!"<no click reached useItemOn>".equals(lastClickAnswer))
            {
                // useItemOn was entered and answered, but not with SUCCESS: the click went out and
                // vanilla turned it down. Distinct from a click that never left.
                clickRefused++;
            }
            else
            {
                // Offset 171 jumps straight here when the build item was empty, so there is no
                // click to have an answer for. Counting it as refused would blame vanilla for
                // Litematica deciding it had nothing to place.
                passed++;
            }
        }
        else
        {
            // Ordinals 1, 10 and 9-as-PASS: no schematic cell on the ray at all, or the ray hit a
            // vanilla block. Nothing to place, nothing broken.
            passed++;
        }

        long now = System.currentTimeMillis();

        if (lastClickLogMs == 0 || now - lastClickLogMs >= ModConfig.get().traceThrottleMs)
        {
            lastClickLogMs = now;

            log("CLICK", "legacy -> doEasyPlaceAction returned " + name
                    + " (areturn ordinal " + ordinal + ")"
                    + "  |  " + answer()
                    + "  [placed=" + placed + " refused=" + clickRefused
                    + " blocked=" + blocked + " passed=" + passed + "]");
        }
    }

    /**
     * Notes that {@code handleEasyPlace} rejected the click although none of
     * {@code doEasyPlaceAction}'s own return sites was seen doing it.
     *
     * <p>Reached from {@code handleEasyPlace}'s fail-message return. It cannot fail the same click
     * twice ({@code snapshotTaken}), so this only writes the first time it happens per click — which
     * is the point: once is evidence, sixty times is noise.</p>
     */
    public static void onLegacyAnsweredElsewhere()
    {
        if (!ModConfig.get().debug || legacyReturnSeen)
        {
            return;
        }

        legacyCalls++;

        lastReturnSite = "<doEasyPlaceAction returned, but not through any of the 11 sites observed>";

        lastFailBranch = "doEasyPlaceAction answered FAIL without running to a return we can see";

        legacyForeignAnswer =
                "EasyPlaceFix cancels doEasyPlaceAction at its getHitType() call (offset 79), and "
                + "handleEasyPlace cannot tell whose answer that was - check whether easyplacefix "
                + "is installed and whether its effective protocol is SLAB_ONLY.";

        log("CLICK", "legacy -> handleEasyPlace printed the fail message, but doEasyPlaceAction's "
                + "own return sites never ran  |  " + legacyForeignAnswer);

        onBlocked(null, Minecraft.getInstance());
    }

    /**
     * One phrase for "what the click actually did", used in the {@code [CLICK]} line and the
     * {@code [BLOCKED]} snapshot.
     */
    private static String answer()
    {
        if (lastClickPlaced)
        {
            return "BLOCK PLACED";
        }

        return lastClickAnswer;
    }

    /**
     * Dumps everything known about a rejected click. Throttled, except for the first few.
     *
     * <p>At most one snapshot per click: {@link #onEasyPlaceFailed()} dedupes via
     * {@link #snapshotTaken}, which is reset at {@code handleEasyPlace} entry.</p>
     *
     * @param reason what rejected the click, or {@code null} when the caller has already put the
     *               verdict into {@link #lastFailBranch}
     */
    public static void onBlocked(String reason, Minecraft mc)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        if (reason == null && snapshotTaken)
        {
            return;
        }

        long now = System.currentTimeMillis();
        boolean unthrottled = snapshotsWritten < ModConfig.get().unthrottledSnapshots;

        if (!unthrottled && now - lastSnapshotMs < ModConfig.get().snapshotThrottleMs)
        {
            return;
        }

        if (reason != null)
        {
            snapshotTaken = true;
        }

        lastSnapshotMs = now;
        snapshotsWritten++;

        captureVanillaHit(mc);

        boolean fixFired = lightBlockQueries > 0;

        log("BLOCKED", "======== EASY PLACE CLICK REJECTED (" + snapshotsWritten + ") ========");
        log("BLOCKED", "rejected because     : " + lastFailBranch);
        log("BLOCKED", "areturn site         : " + lastReturnSite);

        if (reason != null)
        {
            log("BLOCKED", "reported by          : " + reason);
        }

        log("BLOCKED", "live code path      : " + lastPath
                + "   (legacy = easyPlacePostRewrite OFF, post-rewrite = ON)");
        log("BLOCKED", "fix fired at all    : " + (fixFired
                ? "YES - light block shape rewritten " + lightBlockQueries + " time(s)"
                : "NO - the redirect never fired, so the mixin is NOT doing anything"));
        log("BLOCKED", "light block seen    : " + (lastLightPos == null
                ? "<never - the schematic ray trace never hit a minecraft:light>"
                : fmt(lastLightPos) + " " + lastLightState));
        log("BLOCKED", "easy place target   : " + lastTargetInfo);
        log("BLOCKED", "placement restrict. : " + lastRestrictionInfo);
        log("BLOCKED", "canPlaceBlock       : " + lastCanPlaceBlockInfo);
        log("BLOCKED", "click position      : " + lastClickPosInfo);
        log("BLOCKED", "direct light click  : " + lastDirectClickInfo);
        log("BLOCKED", "vanilla said        : " + answer());
        log("BLOCKED", "player sees message : " + lastWithMessage);
        log("BLOCKED", "vanilla crosshair   : " + lastVanillaHitInfo);

        // Printed only when it can say something the other lines cannot: a FAIL message with no
        // observed doEasyPlaceAction return behind it means the method was replaced, not that it
        // vetoed.
        if (!legacyReturnSeen && lastPath.startsWith("legacy"))
        {
            log("BLOCKED", "who said FAIL       : NOT doEasyPlaceAction - none of its 11 return sites"
                    + " ran, yet handleEasyPlace printed the fail message. " + legacyForeignAnswer);
        }

        if (mc.level != null && lastEasyPlaceTargetPos != null)
        {
            BlockPos target = lastEasyPlaceTargetPos;
            BlockState clientState = mc.level.getBlockState(target);
            BlockState schematicState = schematicStateAt(target);

            log("BLOCKED", "at easy place target: client    = " + describe(clientState));
            log("BLOCKED", "                    : schematic = "
                    + (schematicState == null ? "<unavailable>" : describe(schematicState)));
        }

        if (mc.level != null && lastVanillaHitPos != null)
        {
            BlockPos clicked = lastVanillaHitPos;
            BlockState clientState = mc.level.getBlockState(clicked);
            BlockState schematicState = schematicStateAt(clicked);

            log("BLOCKED", "at vanilla crosshair: client    = " + describe(clientState));
            log("BLOCKED", "                    : schematic = "
                    + (schematicState == null ? "<unavailable>" : describe(schematicState)));
        }

        log("BLOCKED", "counters            : lightQueries=" + lightBlockQueries
                + " finished=" + (postRewriteCalls + legacyCalls)
                + " placed=" + placed + " refused=" + clickRefused
                + " blocked=" + blocked + " passed=" + passed
                + " directClicks=" + directLightClicks
                + " restrictionWarned=" + restrictionWarningArmed
                + " unattributedFails=" + unattributedFails);
        log("BLOCKED", "config file         : " + ModConfig.configPath());
        log("BLOCKED", "full log file       : "
                + (ModConfig.get().logToFile ? "logs/easyplace_lightfix.log" : "<disabled>"));
        log("BLOCKED", "================================================================");
    }

    /** Startup banner. */
    public static void printHeader(String litematicaVersion)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        ModConfig config = ModConfig.get();

        log("INIT", "Easy Place Light Fix diagnostics");
        log("INIT", "minecraft            : " + mcVersion());
        log("INIT", "litematica           : " + litematicaVersion);
        log("INIT", "config file          : " + ModConfig.configPath()
                + "  (debug=" + config.debug + ", logToFile=" + config.logToFile + ")");
        log("INIT", "placement works when : [CLICKPOS] and [ROT] appear for a minecraft:light target.");
        log("INIT", "                       That pair is the whole delivery; a [BLOCKED] snapshot"
                + " afterwards is Litematica's own next attempt on an already filled cell, not a"
                + " failure of this mod.");
        log("INIT", "placed means true    : the 'placed=' counter is fed by what useItemOn itself"
                + " answered, not by Easy Place's return value - Easy Place returns PASS when the"
                + " block goes down and SUCCESS when nothing happened, which is the opposite of what"
                + " those names suggest.");
        log("INIT", "refused means vanilla: refused>0 means the click went out and vanilla rejected"
                + " it; the snapshot line 'vanilla said' gives the reason.");
        log("INIT", "no [CLICKPOS] ever   : directClickForLightBlocks is off, so Litematica's"
                + " own click position is in charge. Check directClickForLightBlocks in the config"
                + " file.");
        log("INIT", "no [ROT] after it    : rotateForLightBlocks is off, so the click went out with"
                + " the camera's real rotation and a server that validates reach may refuse it.");
        log("INIT", "[ARMED] lines        : Litematica's own gate (easyPlaceMode on, not the REBUILD"
                + " tool mode, activation key held). They are informational - if placement works,"
                + " ignore them.");
        log("INIT", "easyPlaceClickAdjacent: not consulted by this mod. Both settings"
                + " of that Litematica option produce a light block click.");
        log("INIT", "which path is live  : " + describePath() + ".");
        log("INIT", "easyplacefix         : " + describeEasyPlaceFix() + ".");
        log("INIT", "                       See the README section 'Two Easy Place paths' - the"
                + " legacy path and EasyPlaceFix replace each other, this mod does not conflict"
                + " with either.");
        log("INIT", "Nothing above is a failure report. If placement misbehaves, send"
                + " logs/easyplace_lightfix.log.");
    }

    /**
     * Reads Litematica's {@code EASY_PLACE_POST_REWRITE} reflectively, without a compile-time
     * dependency.
     *
     * <p>Which of the two implementations is live decides almost everything else this mod can do, and
     * getting it wrong produces symptoms that look like a bug in this mod:
     * {@code easyPlacePostRewrite = true} means {@code WorldUtils#doEasyPlaceAction} is never called,
     * which means EasyPlaceFix — which replaces exactly that method — is inert. Stating the value in
     * the startup banner removes the guesswork.</p>
     */
    private static String describePath()
    {
        Object value = readGenericConfigBoolean("EASY_PLACE_POST_REWRITE");

        if (value == null)
        {
            return "could not be read (Litematica internals changed?) - check the 'live code path'"
                    + " line in a [BLOCKED] snapshot";
        }

        return Boolean.TRUE.equals(value)
                ? "easyPlacePostRewrite = ON, so EasyPlaceUtils#handleEasyPlace does the work and"
                        + " this mod's target rescue, direct click and diagnostics are all active"
                : "easyPlacePostRewrite = OFF, so WorldUtils#doEasyPlaceAction does the work -"
                        + " the legacy path needs no click fix (it already clicks the target cell)"
                        + " but this mod's rotation still applies, and EasyPlaceFix is the one in"
                        + " charge";
    }

    /**
     * Whether EasyPlaceFix is installed, by class presence only — never loaded, never touched.
     *
     * <p>This is a compatibility note, not an accusation: a present EasyPlaceFix is reported so that
     * "it stopped working" can be attributed to {@code easyPlacePostRewrite} rather than to this mod,
     * which is what actually happens.</p>
     */
    private static String describeEasyPlaceFix()
    {
        try
        {
            Class.forName("org.uiop.easyplacefix.EasyPlaceFix", false,
                    Diagnostics.class.getClassLoader());
        }
        catch (Throwable t)
        {
            return "not installed";
        }

        return "installed. It hooks WorldUtils#doEasyPlaceAction, which only runs when"
                + " easyPlacePostRewrite = OFF. With that option ON it does nothing at all -"
                + " that is Litematica's switch, not this mod.";
    }

    /** Reads a boolean out of {@code fi.dy.masa.litematica.config.Configs$Generic}, cached per key. */
    private static Object readGenericConfigBoolean(String field)
    {
        try
        {
            Class<?> generic = Class.forName("fi.dy.masa.litematica.config.Configs$Generic", false,
                    Diagnostics.class.getClassLoader());

            Object option = generic.getField(field).get(null);

            return option.getClass()
                    .getMethod("getBooleanValue")
                    .invoke(option);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** Counter dump, printed on shutdown. */
    public static void printSummary()
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        log("SUMMARY", "lightQueries=" + lightBlockQueries
                + " finished=" + (postRewriteCalls + legacyCalls)
                + " placed=" + placed + " refused=" + clickRefused
                + " blocked=" + blocked + " passed=" + passed
                + " directClicks=" + directLightClicks
                + " restrictionWarned=" + restrictionWarningArmed
                + " unattributedFails=" + unattributedFails
                + " lastLightPos=" + (lastLightPos == null ? "<never>" : fmt(lastLightPos)));

        if (clickRefused > 0)
        {
            log("SUMMARY", "NOTE: refused>0 means Easy Pick Place asked for the block and vanilla"
                    + " would not place it. Last answer: " + lastClickAnswer
                    + " - see the 'vanilla said' line of a [BLOCKED] snapshot for the position.");
        }
        log("SUMMARY", "last rejected because: " + lastFailBranch);
        log("SUMMARY", "last areturn site    : " + lastReturnSite);

        if (directLightClicks == 0 && lightBlockQueries > 0)
        {
            log("SUMMARY", "VERDICT: the ray-trace fix is live but the direct click never fired -"
                    + " check directClickForLightBlocks.");
        }
        else if (directLightClicks > 0)
        {
            log("SUMMARY", "VERDICT: delivery fired " + directLightClicks + " time(s); this mod"
                    + " is doing its job.");
        }
        else
        {
            log("SUMMARY", "VERDICT: nothing fired. The ray trace never saw a minecraft:light, so"
                    + " Easy Place never picked one.");
        }
    }

    /**
     * Reports that one of the delivery hooks threw and was caught.
     *
     * <p>Once per distinct hook per session: a hook that throws throws every tick, and the whole
     * point of catching it is that the game keeps running, so there is no need to be told 60 times a
     * second.</p>
     */
    public static void onHookFailed(String hook, Throwable t)
    {
        String id = hook + "/" + t.getClass().getName();

        if (reportedHookFailures.contains(id))
        {
            return;
        }

        reportedHookFailures.add(id);

        log("HOOK-FAIL", hook + " threw and was caught; Litematica's stock behaviour was used for"
                + " this click. The click still went out, so the block may be missing rather than"
                + " the game crashing. " + t);
    }

    // --- internals -------------------------------------------------------------------------------

    private static final java.util.Set<String> reportedHookFailures = new java.util.HashSet<>();

    private static void captureVanillaHit(Minecraft mc)
    {
        HitResult hit = mc.hitResult;

        if (hit == null || hit.getType() != HitResult.Type.BLOCK)
        {
            lastVanillaHitPos = null;
            lastVanillaHitInfo = "<no block hit>";

            return;
        }

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = mc.level == null ? null : mc.level.getBlockState(pos);
        String distance = "<unknown>";

        if (mc.player != null)
        {
            distance = String.format(java.util.Locale.ROOT, "%.2f",
                    Math.sqrt(blockHit.getLocation().distanceToSqr(mc.player.getEyePosition())));
        }

        lastVanillaHitPos = pos.immutable();
        lastVanillaHitInfo = "pos=" + fmt(pos)
                + " face=" + blockHit.getDirection()
                + " block=" + (state == null ? "?" : describe(state))
                + " dist=" + distance;
    }

    /**
     * Reads a block state from Litematica's schematic world without a compile-time dependency on
     * Litematica.
     *
     * <p>Delegates to {@link SchematicAccess}, which is shared with the delivery logic — a schematic
     * read is needed both to describe a failure and to decide whether a target is a light block, and
     * the two must not disagree about which Litematica version they are talking to.</p>
     */
    private static BlockState schematicStateAt(BlockPos pos)
    {
        return SchematicAccess.tryGetState(pos);
    }

    // --- Printer-style delivery ------------------------------------------------------------------

    /**
     * Records that a lost Easy Place target was recovered from the schematic-only trace. This is the
     * single most useful line for judging whether {@link PrinterDelivery#rescueLightTarget()} is
     * doing anything at all — if it never appears, Easy Place was failing for a different reason.
     */
    public static void onTargetRescued(BlockHitResult hit)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        log("RESCUED", "Easy Place target was discarded, recovered "
                + fmt(hit.getBlockPos()) + " face=" + hit.getDirection()
                + " schematic=" + describe(schematicStateAt(hit.getBlockPos())));
    }

    /** Records the server-side rotation sent for a light block click. */
    public static void onFakeRotation(Direction face, float yaw, float pitch)
    {
        if (!ModConfig.get().debug)
        {
            return;
        }

        log("ROT", "sent ServerboundMovePlayerPacket.Rot yaw=" + fmt1(yaw)
                + " pitch=" + fmt1(pitch) + " face=" + face + " (camera unchanged)");
    }

    /**
     * Records the direct click that replaced Litematica's solid-neighbour search.
     *
     * <p>Logged the first time unconditionally, because its absence is the single most damning thing
     * a log can say about this mod: a {@code [BLOCKED]} snapshot with no {@code [CLICKPOS]} and
     * {@code click position: <null>} means the substitution never fired and placement was never even
     * attempted. Throttled afterwards, since it runs every tick while Easy Place is held down.</p>
     */
    public static void onDirectLightClick(BlockHitResult hit)
    {
        directLightClicks++;

        if (!ModConfig.get().debug)
        {
            return;
        }

        lastDirectClickInfo = fmt(hit.getBlockPos()) + " face=" + hit.getDirection()
                + " at " + hit.getLocation();

        boolean first = directLightClicks == 1;
        long now = System.currentTimeMillis();

        if (!first && now - lastDirectLogMs < ModConfig.get().traceThrottleMs)
        {
            return;
        }

        lastDirectLogMs = now;

        log("CLICKPOS", (first ? "direct light click is live -> " : "")
                + "clicking the target cell itself at " + fmt(hit.getBlockPos())
                + " face=" + hit.getDirection()
                + " (getClickPosition's answer was replaced, so this works with"
                + " easyPlaceClickAdjacent either on or off)"
                + "  [directClicks=" + directLightClicks + "]");
    }

    /** {@code minecraft:light}, never a localised name, so the log is language independent. */
    private static String describe(BlockState state)
    {
        if (state == null)
        {
            return "<null>";
        }

        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String name = id != null ? id.toString() : String.valueOf(state.getBlock());

        return name + (state.isAir() ? "" : " [replaceable=" + state.canBeReplaced() + "]");
    }

    private static String shapeInfo(VoxelShape shape)
    {
        return shape.isEmpty() ? "EMPTY" : ("boxes=" + shape.toAabbs().size());
    }

    private static String fmt(BlockPos pos)
    {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    /** One decimal place, so a yaw of -90.0 reads cleanly in the log. */
    private static String fmt1(float value)
    {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String mcVersion()
    {
        try
        {
            return SharedConstants.getCurrentVersion().name();
        }
        catch (Throwable t)
        {
            return "unknown";
        }
    }

    /** Writes one tagged line to the console and, if enabled, appends it to the dedicated log. */
    static void log(String tag, String message)
    {
        String line = "[" + tag + "] " + message;
        System.out.println("[EasyPlaceLightFix/" + tag + "] " + message);

        if (!ModConfig.get().logToFile || !fileWritable)
        {
            return;
        }

        Path path = Paths.get("logs", "easyplace_lightfix.log");

        try
        {
            if (!filePrepared)
            {
                Files.createDirectories(path.getParent());
                Files.deleteIfExists(path);
                filePrepared = true;
            }

            Files.writeString(path,
                    LocalTime.now().format(TIME) + " " + line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            // Give up on the file after the first failure so we do not spam the console.
            fileWritable = false;
            System.err.println("[EasyPlaceLightFix] Cannot write logs/easyplace_lightfix.log ("
                    + e + "). Continuing with console logging only.");
        }
    }
}