package notoshi.easyplacelightfix.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes {@code minecraft:light} placeable through Litematica's Easy Place.
 *
 * <h2>The problem</h2>
 * In vanilla, {@code LightBlock#getShape} returns a full cube <b>only while the player is holding a
 * light block</b>, and an empty shape otherwise. Litematica picks the Easy Place target with its own
 * ray trace over the schematic world, in {@code RayTraceUtils#traceFirstStep} and
 * {@code RayTraceUtils#traceLoopSteps}, where the shape is gated like this:
 *
 * <pre>
 *     VoxelShape blockShape = blockState.getShape(world, pos, CollisionContext.of(player));
 *     boolean blockCollidable = !blockShape.isEmpty();   // always false for a light block
 * </pre>
 *
 * <p>With an empty shape the block is skipped entirely, the ray passes straight through it and the
 * Easy Place target is lost. What follows is the fallback branch:
 * {@code EasyPlaceUtils#placementRestrictionInEffect} reads the position from the <b>vanilla</b>
 * {@code mc.hitResult} (which also passes through the light block), sees an unfilled schematic
 * position there and returns {@code InteractionResult.FAIL}, producing the user-facing message
 * {@code litematica.message.easy_place_fail} ("Action blocked because of Easy Place mode").
 *
 * <p>This is a genuine deadlock: the build item is only moved into the player's hand
 * <i>after</i> the target has been found ({@code InventoryUtils.schematicWorldPickBlock} runs later
 * down the call chain), so a light block can never be placed as the very first click.</p>
 *
 * <h2>The fix</h2>
 * Report a full cube for {@code minecraft:light} inside Litematica's ray trace. This reproduces
 * vanilla's own "a light block is held" shape, so:
 * <ul>
 *   <li>the Easy Place target is found instead of being skipped;</li>
 *   <li>the fallback into {@code placementRestrictionInEffect} is never reached, so the false
 *       "action blocked" message no longer appears;</li>
 *   <li>the required build item still gets swapped into the hand by Easy Place as usual.</li>
 * </ul>
 *
 * <h2>Coverage</h2>
 * A single {@code @Redirect} covers everything, because every Easy Place path funnels into
 * {@code traceFirstStep} / {@code traceLoopSteps}:
 * <ul>
 *   <li>both target-selection branches — {@code EASY_PLACE_FIRST} (default {@code true}) and
 *       {@code getFurthestSchematicWorldTraceBeforeVanilla};</li>
 *   <li>both Litematica code paths — the legacy {@code WorldUtils#doEasyPlaceAction} (used while
 *       {@code easyPlacePostRewrite = false}, i.e. by default) and the rewritten
 *       {@code EasyPlaceUtils#handleEasyPlace};</li>
 *   <li>Litematica's other schematic traces too (block info overlay, verifier, pick block), which
 *       is a side benefit: light blocks become hoverable and clickable there as well.</li>
 * </ul>
 *
 * <h2>What is intentionally NOT changed</h2>
 * <ul>
 *   <li><b>Vanilla is untouched.</b> {@code LightBlock#getShape} is not modified, so the crosshair
 *       still does not snap onto light blocks and the standard hit test ignores them.</li>
 *   <li><b>The collision shape is untouched</b>, so you can still walk through light blocks and they
 *       neither block your view nor absorb light.</li>
 *   <li><b>The click itself is not faked.</b> The very same {@code ServerboundUseItemOnPacket} as a
 *       normal manual right-click is sent, and the camera never moves.</li>
 *   <li><b>No server-side component</b> and no dependency on Litematica Printer.</li>
 * </ul>
 *
 * <h2>The rotation this file does not send</h2>
 *
 * <p>{@link PrinterDelivery} sends Litematica Printer's
 * {@code ServerboundMovePlayerPacket.Rot} for light targets, so the server does briefly see a look
 * direction the client never rendered. That is inherent to the technique, and
 * {@code rotateForLightBlocks} turns it off.</p>
 *
 * <p>This {@code @Redirect} targets {@code BlockState#getShape}, an instance method, so Mixin puts
 * the receiver first in the handler signature and the return value is unambiguous — it <i>is</i>
 * the handler's return type. A {@code @Redirect} on a static target is the opposite: the handler can
 * neither observe the original return value nor call the target without recursing.</p>
 */
@Mixin(targets = "fi.dy.masa.litematica.util.RayTraceUtils")
public class MixinRayTraceUtils
{
    /**
     * Descriptor of {@code BlockState#getShape(BlockGetter, BlockPos, CollisionContext)}, verified
     * against {@code net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase} in the
     * Minecraft 26.2 {@code client.jar}, and against the actual bytecode of Litematica 0.28.8
     * ({@code invokevirtual BlockState.getShape}), which contains exactly one such call in each of
     * {@code traceFirstStep} and {@code traceLoopSteps} — so no {@code ordinal} is needed.
     *
     * <p><b>The handler must be {@code static}.</b> {@code RayTraceUtils} is a pure static utility
     * class in Litematica, and Mixin refuses a non-static callback on a static target:</p>
     *
     * <pre>
     *     InvalidInjectionException: non-static callback method
     *     ...RayTraceUtils::notoshi$lightBlockIsSolidForTrace targets a static method which is not supported
     * </pre>
     */
    @Redirect(
            method = { "traceFirstStep", "traceLoopSteps" },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;"
                           + "getShape(Lnet/minecraft/world/level/BlockGetter;"
                           + "Lnet/minecraft/core/BlockPos;"
                           + "Lnet/minecraft/world/phys/shapes/CollisionContext;)"
                           + "Lnet/minecraft/world/phys/shapes/VoxelShape;"
            )
    )
    private static VoxelShape notoshi$lightBlockIsSolidForTrace(BlockState blockState,
                                                               BlockGetter world,
                                                               BlockPos pos,
                                                               CollisionContext context)
    {
        if (blockState.is(Blocks.LIGHT))
        {
            // Shapes.block() is the full 0..1 cube on all three axes (called Shapes.fullCube()
            // before 26.x). This is precisely the shape LightBlock reports while a light block is
            // held, i.e. we reproduce vanilla's "a light block is in hand" behaviour.
            return Shapes.block();
        }

        return blockState.getShape(world, pos, context);
    }
}