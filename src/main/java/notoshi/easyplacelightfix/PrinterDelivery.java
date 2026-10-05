package notoshi.easyplacelightfix;

import java.lang.reflect.Method;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Easy Place decides, Printer delivers."
 *
 * <p>The split, and why it is split that way:</p>
 * <ul>
 *   <li><b>Litematica decides.</b> Which block goes where, against which face, with which item — all
 *       of that stays Litematica's: {@code getTargetPosition}, {@code getClickPosition},
 *       {@code applyPlacementProtocolAll}, {@code canPlaceBlock}, {@code MaterialCache}. Nothing here
 *       ever invents a placement decision.</li>
 *   <li><b>Printer delivers.</b> When the decided target is a {@code minecraft:light}, add
 *       Litematica Printer's server-side rotation so the server evaluates reach and line-of-sight
 *       against the block being placed rather than against wherever the player is actually
 *       facing.</li>
 * </ul>
 *
 * <h2>Why the rotation has to be packet-only</h2>
 * Printer sends {@link ServerboundMovePlayerPacket.Rot} carrying the yaw/pitch of the target face
 * and never calls {@code setYRot}/{@code setXRot} on the local player. The client keeps the real
 * rotation, so the camera never snaps, while the server's picture of where the player is looking is
 * briefly overridden. The derivation mirrors Printer's {@code PrepareAction}:
 *
 * <pre>
 *     horizontal face -&gt; yaw = face.toYRot(), pitch = 0
 *     UP              -&gt; pitch = -90   (yaw untouched)
 *     DOWN            -&gt; pitch = +90   (yaw untouched)
 * </pre>
 *
 * <p><b>This is visible to the server.</b> The server sees a look direction the client never
 * rendered, which is strictly more server-visible than an ordinary placement. That is inherent to
 * the technique rather than to this mod's implementation of it, and {@code rotateForLightBlocks}
 * turns it off.</p>
 *
 * <h2>Delivery is only half of it</h2>
 * A rotation helps only once there is something to deliver. Easy Place bails out with
 * {@code FAIL} <b>before</b> it ever builds a click when {@code getTargetPosition} returns
 * {@code null}, and it does that whenever the closest hit along the ray is a vanilla block rather
 * than a schematic block. {@link #rescueLightTarget()} recovers that target using Litematica's own
 * schematic-only trace — still Litematica's decision, just recovered instead of discarded.
 *
 * <h2>The bail-out that actually stopped light blocks</h2>
 * Having a target is still not enough. {@code getClickPosition} is where the click is decided, and it
 * branches on {@code EASY_PLACE_CLICK_ADJACENT}:
 *
 * <pre>
 *     if (EASY_PLACE_CLICK_ADJACENT.getBooleanValue())
 *         return getAdjacentClickPosition(targetPos);   // neighbour search
 *     return hitResult;                                 // &lt;- this mod hooks here
 * </pre>
 *
 * <p>On the {@code true} branch {@code getAdjacentClickPosition} has to find <em>some block to
 * click</em>, because vanilla cannot click air. Its fallback loop takes the first neighbour of the
 * target that is <b>not</b> replaceable:</p>
 *
 * <pre>
 *     for (Direction dir : Direction.values()) {
 *         BlockPos neighbour = targetPos.relative(dir);
 *         if (!PlacementUtils.isReplaceable(level, neighbour, false))
 *             return new BlockHitResult(facePoint(neighbour, dir), dir.getOpposite(), neighbour, false);
 *     }
 *     return null;                    // &lt;- every neighbour was air
 * </pre>
 *
 * <p>A light block in mid-air has six neighbours of air, so the loop finds no support, returns
 * {@code null}, and {@code handleEasyPlace} bails with {@code FAIL} at its
 * {@code clickPos == null || hand == null} check — with a valid target, a valid item and a valid
 * hand. That is the exact state the diagnostics report as {@code click position: <null>} with
 * {@code canPlaceBlock: true}.</p>
 *
 * <p>{@link #directLightClick(BlockPos)} replaces the answer for light targets with a click on the
 * target cell itself. {@code BlockItem} places into whatever
 * {@code BlockPlaceContext#canPlace()} accepts, which for air is
 * {@code state.canBeReplaced(context)} — true — so the server accepts a click on an empty cell and
 * fills it. That is the same trick Litematica Printer's "replaceable" mode relies on.</p>
 *
 * <p><b>Why the hook sits on {@code getClickPosition} and not on
 * {@code getAdjacentClickPosition}.</b> The latter is only ever called on the {@code true} branch, so
 * hooking it there would make the mod a silent no-op whenever {@code easyPlaceClickAdjacent} is off.
 * {@code getClickPosition} is the single point both branches pass through, so one hook covers the
 * option being on or off. The price is that Litematica's neighbour search still runs before the result
 * is replaced; it costs one ray trace per light block click.</p>
 *
 * <h2>Why the rotation sits in {@code useItemOn}</h2>
 * Litematica has two completely separate Easy Place implementations and a config option picks
 * between them:
 *
 * <pre>
 *     WorldUtils.handleEasyPlace(mc):
 *         if (EASY_PLACE_POST_REWRITE.getBooleanValue()) return false;   // -&gt; EasyPlaceUtils
 *         return doEasyPlaceAction(mc);                                   // -&gt; WorldUtils
 * </pre>
 *
 * <p>EasyPlaceFix replaces {@code WorldUtils#doEasyPlaceAction} and its own tick queue calls
 * {@code MultiPlayerGameMode#useItemOn} directly, so while that mod is in charge
 * {@code EasyPlaceUtils#handleEasyPlace} is never reached. A hook on the {@code useItemOn} call
 * <i>inside</i> {@code handleEasyPlace} therefore cannot fire in that configuration at all. Injecting
 * at the head of {@code useItemOn} itself — a vanilla method every path funnels through — makes the
 * mod compose with EasyPlaceFix instead of being mutually exclusive with it.</p>
 */
public final class PrinterDelivery
{
    /**
     * Litematica's schematic-only trace, resolved reflectively.
     *
     * <p>Public and static in 0.28.8:
     * {@code traceToSchematicWorld(Entity, double, boolean, boolean) -&gt; BlockHitResult}. This is
     * the same method {@code getGenericTrace} calls to produce the schematic half of the ray, so
     * reusing it means the rescue cannot disagree with Easy Place about what the ray found.</p>
     */
    private static Method traceToSchematicWorld;

    /** Resolved once; a miss is permanent, so this never turns into a per-click {@code Class.forName}. */
    private static boolean traceResolved;

    /**
     * Deduplication state for the rotation, so one click produces exactly one
     * {@link ServerboundMovePlayerPacket.Rot}.
     *
     * <p>Needed because the rotation is now sent from the head of
     * {@link net.minecraft.client.multiplayer.MultiPlayerGameMode#useItemOn}, and for a light block
     * that method is genuinely entered <b>twice</b>: once by vanilla for the player's own
     * right-click, and once more from inside Litematica's Easy Place, which is triggered by that same
     * right-click. Both carry the same target cell, and one rotation is all the server needs.</p>
     *
     * <p>Game time rather than a nesting counter, because a head injection has no "after the call"
     * moment to decrement on: by the time the inner {@code useItemOn} runs, the outer one has already
     * returned. Keyed on the position as well as the tick, because EasyPlaceFix-style queuing really
     * can place two different light blocks within one tick and each of those wants its own
     * rotation.</p>
     */
    private static BlockPos lastRotatedPos;

    private static long lastRotatedTick = Long.MIN_VALUE;

    private PrinterDelivery() {}

    // --- hook plumbing ---------------------------------------------------------------------------

    /**
     * Sends the Printer-style rotation for a light block click, once.
     *
     * <p>Called from the head of {@code MultiPlayerGameMode#useItemOn}, so it is
     * <b>path-agnostic</b>: it fires for Litematica's rewritten path
     * ({@code easyPlacePostRewrite = true}), for Litematica's legacy path, and for a click made by
     * EasyPlaceFix's own tick queue, because all three end up calling that one vanilla method. A hook
     * on the {@code useItemOn} call <i>inside</i> {@code EasyPlaceUtils#handleEasyPlace} would fire
     * on the rewritten path only, and therefore never at all while EasyPlaceFix is in charge, since
     * EasyPlaceFix replaces {@code WorldUtils#doEasyPlaceAction} and never reaches the rewritten
     * method.</p>
     *
     * <p>Deliberately sends the rotation and nothing else: the click itself is not modified here, so
     * every other block — and every light cell that is already occupied — is untouched.</p>
     */
    public static void onUseItemOn(LocalPlayer player, BlockHitResult hitResult)
    {
        try
        {
            if (!ModConfig.get().rotateForLightBlocks || player == null || hitResult == null)
            {
                return;
            }

            Minecraft mc = Minecraft.getInstance();

            if (mc.level == null)
            {
                return;
            }

            BlockPos pos = hitResult.getBlockPos();

            // Only a click that is actually about to place a light block gets a rotation. Without
            // the canBeReplaced() test, every right-click on an already placed light block would send
            // one too - including a vanilla attempt to break it.
            if (!mc.level.getBlockState(pos).canBeReplaced() || !isLightTarget(pos))
            {
                return;
            }

            long tick = mc.level.getGameTime();

            if (tick == lastRotatedTick && pos.equals(lastRotatedPos))
            {
                return;
            }

            lastRotatedTick = tick;
            lastRotatedPos = pos.immutable();

            sendFakeRotation(player, hitResult.getDirection());
        }
        catch (Throwable t)
        {
            Diagnostics.onHookFailed("onUseItemOn", t);
        }
    }

    /**
     * Records what {@code MultiPlayerGameMode#useItemOn} actually answered.
     *
     * <p>Needed because nothing downstream of it can be trusted to say. Litematica's rewritten path
     * inspects the answer only against {@link InteractionResult#PASS} and reports the opposite of
     * what it means — {@code handleEasyPlace} returns {@code PASS} when the block is down and
     * {@code SUCCESS} when nothing happened (see {@code MixinMultiPlayerGameModeDelivery} for the
     * bytecode). The legacy path compares against {@code SUCCESS} plus a swing source. Neither is a
     * verdict a human can read off a counter.</p>
     *
     * <p>Overwritten rather than accumulated, on purpose: for a light block {@code useItemOn} is
     * entered twice per click, and only the <b>last</b> return before Litematica returns is the one
     * that describes the Easy Place placement. The earlier one belongs to the player's own
     * right-click that triggered it.</p>
     *
     * <p>{@code null} is reported as such rather than coerced. {@code useItemOn} reads the result out
     * of a {@code MutableObject} that the predicted action writes, so a null is a real possibility
     * if the prediction never ran — and it means something entirely different from {@code FAIL}.</p>
     *
     * @param returnOrdinal which of {@code useItemOn}'s two {@code areturn} sites this was:
     *                      0 = the world-border bail-out, 1 = the prediction result
     */
    public static void onUseItemOnResult(LocalPlayer player,
                                         BlockHitResult hitResult,
                                         InteractionResult result,
                                         int returnOrdinal)
    {
        try
        {
            if (!ModConfig.get().debug)
            {
                return;
            }

            String verdict;

            if (result == null)
            {
                verdict = "null - the predicted action never wrote a result, so the click was sent"
                        + " but nothing ran client-side";
            }
            else if (returnOrdinal == 0)
            {
                verdict = "FAIL - the click is outside the world border (useItemOn offset 27,"
                        + " returned before the prediction started)";
            }
            else if (result == InteractionResult.SUCCESS)
            {
                verdict = "SUCCESS - the block was placed";
            }
            else if (result == InteractionResult.CONSUME)
            {
                verdict = "CONSUME - consumed without swinging (this is what spectator mode returns)";
            }
            else if (result == InteractionResult.PASS)
            {
                verdict = "PASS - the item did nothing at this position";
            }
            else if (result == InteractionResult.FAIL)
            {
                verdict = "FAIL - vanilla refused the placement (BlockItem#place returns FAIL when"
                        + " the cell is not replaceable, the block cannot survive, or setBlock"
                        + " refused)";
            }
            else
            {
                verdict = String.valueOf(result);
            }

            Diagnostics.onUseItemOnResult(verdict, result == InteractionResult.SUCCESS);
        }
        catch (Throwable t)
        {
            Diagnostics.onHookFailed("onUseItemOnResult", t);
        }
    }

    /**
     * Reports that one of the delivery hooks threw.
     *
     * <p>The hooks already caught the throwable, so the click continues with stock Litematica
     * behaviour. What is left to do is tell the user <i>why</i> the mod quietly did nothing, since a
     * silent patch mod is indistinguishable from a broken one. Throttled, because a hook that throws
     * would otherwise throw on every tick.</p>
     */
    public static void onHookFailed(String hook, Throwable t)
    {
        Diagnostics.onHookFailed(hook, t);
    }

    // --- target rescue ---------------------------------------------------------------------------

    /**
     * Recovers the Easy Place target for a light block that Litematica gave up on.
     *
     * <p>Called from the {@code RETURN} of {@code EasyPlaceUtils#getTargetPosition} when it returned
     * {@code null}. Deliberately narrow:</p>
     * <ul>
     *   <li>only a {@code minecraft:light} target is accepted — no other aspect of Litematica's
     *       targeting is touched;</li>
     *   <li>the trace uses {@code fluids = false}: a light block is never a fluid, and the real value
     *       comes from a Litematica config option this mod does not read;</li>
     *   <li>the range is {@code min(reach, 6.0)}, the same clamp Litematica applies to its own Easy
     *       Place trace, so the rescue cannot reach further than Easy Place would have.</li>
     * </ul>
     *
     * @return the recovered hit, or {@code null} if there was nothing to recover
     */
    public static BlockHitResult rescueLightTarget()
    {
        Minecraft mc = Minecraft.getInstance();

        if (!ModConfig.get().rescueLightTarget || mc.player == null || mc.level == null)
        {
            return null;
        }

        Method trace = resolveTrace();

        if (trace == null)
        {
            return null;
        }

        LocalPlayer player = mc.player;
        double range = Math.min(player.blockInteractionRange(), 6.0D);

        BlockHitResult hit;

        try
        {
            hit = (BlockHitResult) trace.invoke(null, player, range, false, false);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            // Litematica restructured, or the schematic world is not ready. Non-fatal.
            return null;
        }

        if (hit == null || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || !isLightTarget(hit.getBlockPos()))
        {
            return null;
        }

        Diagnostics.onTargetRescued(hit);

        return hit;
    }

    private static Method resolveTrace()
    {
        if (traceResolved)
        {
            return traceToSchematicWorld;
        }

        traceResolved = true;

        try
        {
            traceToSchematicWorld = Class.forName("fi.dy.masa.litematica.util.RayTraceUtils")
                    .getMethod("traceToSchematicWorld", net.minecraft.world.entity.Entity.class,
                            double.class, boolean.class, boolean.class);
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            traceToSchematicWorld = null;
            System.err.println("[EasyPlaceLightFix] Cannot reach RayTraceUtils#traceToSchematicWorld"
                    + " (" + e + "). Light target rescue is disabled.");
        }

        return traceToSchematicWorld;
    }

    // --- direct click ---------------------------------------------------------------------------

    /**
     * Builds a click on the target cell itself, replacing Litematica's "find a solid block to click"
     * search for light blocks.
     *
     * <p>Guarded as narrowly as possible, so that stock placement behaviour is preserved wherever
     * this mod has no reason to intervene:</p>
     * <ul>
     *   <li>{@code directClickForLightBlocks} off — the user asked for stock behaviour;</li>
     *   <li>the target is a {@code minecraft:light} <b>in the schematic</b>, so nothing else is
     *       affected;</li>
     *   <li>the cell is still replaceable <b>in the client world</b>. If something solid is
     *       actually standing there, Litematica's adjacent click is the correct answer and this
     *       hands it back by returning {@code null};</li>
     *   <li>a player and level exist.</li>
     * </ul>
     *
     * <p>The click location is the centre of the face pointing back at the player's eye, which is
     * the closest point of the cell and mirrors what {@code getAdjacentClickPosition} builds for its
     * own hits. The server only ever range-checks the {@link BlockPos}, not the vector.</p>
     *
     * @return the click to use, or {@code null} to let Litematica compute its own
     */
    public static BlockHitResult directLightClick(BlockPos targetPos)
    {
        Minecraft mc = Minecraft.getInstance();

        if (!ModConfig.get().directClickForLightBlocks
                || targetPos == null || mc.player == null || mc.level == null)
        {
            return null;
        }

        // Litematica's own answer is better when the cell is genuinely occupied: an adjacent solid
        // block can still be clicked, so there is nothing to rescue.
        if (!mc.level.getBlockState(targetPos).canBeReplaced())
        {
            return null;
        }

        if (!isLightTarget(targetPos))
        {
            return null;
        }

        Direction face = faceTowardsEye(targetPos, mc.player.getEyePosition());

        // Centre of the face that points back at the eye. This is the closest point of the cell, so
        // it can only ever help with a range check, and it is what getAdjacentClickPosition builds
        // for its own hits.
        Vec3 location = Vec3.atLowerCornerWithOffset(targetPos,
                face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);

        BlockHitResult hit = new BlockHitResult(location, face, targetPos, false);

        Diagnostics.onDirectLightClick(hit);

        // The debug hook that observes getClickPosition' own return sits in the optional mixin
        // config, and it is at the very instruction this injection cancels at - so it would never
        // run for a click this method produced. Recorded here instead, which is the honest value
        // anyway: this is the click Litematica is about to use.
        Diagnostics.onClickPosition(hit);

        return hit;
    }

    /**
     * The axis-aligned face of {@code pos} that {@code eye} is looking at, chosen by the largest
     * offset — the same tie-break as {@code Direction.orderedByNearest}.
     */
    private static Direction faceTowardsEye(BlockPos pos, Vec3 eye)
    {
        double x = eye.x - (pos.getX() + 0.5D);
        double y = eye.y - (pos.getY() + 0.5D);
        double z = eye.z - (pos.getZ() + 0.5D);

        double ax = Math.abs(x);
        double ay = Math.abs(y);
        double az = Math.abs(z);

        if (ax >= ay && ax >= az)
        {
            return x > 0.0D ? Direction.EAST : Direction.WEST;
        }

        if (ay >= az)
        {
            return y > 0.0D ? Direction.UP : Direction.DOWN;
        }

        return z > 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    // --- delivery --------------------------------------------------------------------------------

    /**
     * Whether {@code pos} is a {@code minecraft:light} cell in the loaded schematic.
     *
     * <p>Read from the schematic rather than the client world, because the block is not in the client
     * world yet — placing it is the entire point.</p>
     */
    public static boolean isLightTarget(BlockPos pos)
    {
        if (pos == null)
        {
            return false;
        }

        BlockState state = SchematicAccess.tryGetState(pos);

        return state != null && state.is(Blocks.LIGHT);
    }

    /**
     * Sends a single {@link ServerboundMovePlayerPacket.Rot} aiming the <b>server's</b> view of the
     * player at the face Litematica decided to click.
     *
     * <p>Does not touch the local player's rotation — that is what keeps the camera still, and it is
     * exactly what Printer does.</p>
     *
     * <p>Only the one packet is sent, not the sustained rewrite Printer performs through its own
     * {@code ServerboundMovePlayerPacket} mixin. Litematica performs the click on the very same tick,
     * and both packets leave through the same connection in order, so the server still has the
     * overridden rotation when the placement arrives. If testing ever shows the rotation arriving too
     * late to matter, the sustained rewrite is the next step.</p>
     */
    public static void sendFakeRotation(LocalPlayer player, Direction face)
    {
        if (!ModConfig.get().rotateForLightBlocks || player == null || player.connection == null
                || face == null)
        {
            return;
        }

        float yaw = player.getYRot();
        float pitch;

        if (face.getAxis().isHorizontal())
        {
            yaw = face.toYRot();
            pitch = 0.0F;
        }
        else if (face == Direction.UP)
        {
            pitch = -90.0F;
        }
        else
        {
            pitch = 90.0F;
        }

        player.connection.send(new ServerboundMovePlayerPacket.Rot(
                yaw, pitch, player.onGround(), player.horizontalCollision));

        Diagnostics.onFakeRotation(face, yaw, pitch);
    }
}