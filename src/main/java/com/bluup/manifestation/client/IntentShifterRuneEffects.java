package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.server.block.IntentRelayBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class IntentShifterRuneEffects {
    private static final int DEFAULT_FALLBACK_TICKS = 20;
    private static final int FADE_OUT_TICKS = 16;
    private static final int RUNE_SLOTS = 7;
    private static final double RING_RADIUS = 0.32;
    private static final double DECAL_DEPTH_BLOCKS = 0.2;
    private static final double FACE_SURFACE_OFFSET = 0.03;
    // These constants control the how far from the face the rune ring appears cos it's not a full block width. Maybe I make this a mod config?
    private static final double WORLD_Y_LIFT = 0.08;
    private static final float RUNE_SCALE = 0.018f;
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final String[] RUNES = {"ᚠ", "ᚢ", "ᚦ", "ᚨ", "ᚱ", "ᚲ", "ᚹ", "ᛇ", "ᛉ", "ᛟ"};

    private static final Map<Long, ActiveRunes> ACTIVE = new HashMap<>();

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.INTENT_SHIFTER_RUNES_S2C,
            (client, handler, buf, responseSender) -> {
                BlockPos pos = buf.readBlockPos();
                Direction outward = buf.readEnum(Direction.class);
                int durationTicks = Math.max(1, buf.readVarInt());
                client.execute(() -> ACTIVE.put(pos.asLong(), new ActiveRunes(pos, outward, durationTicks)));
            }
        );

        ClientTickEvents.END_CLIENT_TICK.register(IntentShifterRuneEffects::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> renderRunes(Minecraft.getInstance(), context.matrixStack()));
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused() || ACTIVE.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<Long, ActiveRunes>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            ActiveRunes runes = it.next().getValue();
            runes.age++;

            BlockState state = mc.level.getBlockState(runes.pos);
            boolean isStillActive = state.hasProperty(IntentRelayBlock.Companion.getACTIVE())
                && state.getValue(IntentRelayBlock.Companion.getACTIVE());

            if (runes.fading || !isStillActive) {
                runes.fading = true;
                if (runes.fadeAge < 0) {
                    runes.fadeAge = 0;
                } else {
                    runes.fadeAge++;
                }
            }

            if (runes.fading && runes.fadeAge >= FADE_OUT_TICKS) {
                it.remove();
                continue;
            }

            runes.stillActive = !runes.fading && isStillActive;
        }
    }

    private static void renderRunes(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return;
        }

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        for (ActiveRunes runes : ACTIVE.values()) {
            Vec3 outward = new Vec3(runes.outward.getStepX(), runes.outward.getStepY(), runes.outward.getStepZ());
            Vec3 right;
            Vec3 up;

            if (Math.abs(outward.y) > 0.5) {
                right = new Vec3(1, 0, 0);
                up = new Vec3(0, 0, 1);
            } else if (Math.abs(outward.x) > 0.5) {
                right = new Vec3(0, 0, 1);
                up = new Vec3(0, 1, 0);
            } else {
                right = new Vec3(1, 0, 0);
                up = new Vec3(0, 1, 0);
            }

            // The shifter is a thin decal near one side of the block space, not a full cube.
            // This places runes relative to the rendered face rather than full-block depth.
            double renderedFaceCenterAlongOutward = -(0.5 - DECAL_DEPTH_BLOCKS);
            Vec3 origin = Vec3.atCenterOf(runes.pos)
                .add(outward.scale(renderedFaceCenterAlongOutward + FACE_SURFACE_OFFSET))
                .add(0.0, WORLD_Y_LIFT, 0.0);

            float progress = Mth.clamp(runes.age / (float) runes.fallbackDurationTicks, 0f, 1f);
            int revealed = Math.max(1, Mth.floor(progress * RUNE_SLOTS));
            if (!runes.stillActive) {
                revealed = RUNE_SLOTS;
            }

            float fade = runes.fade();

            for (int i = 0; i < revealed; i++) {
                double baseAngle = ((Math.PI * 2.0) / RUNE_SLOTS) * i;
                double spin = runes.age * 0.015;
                double angle = baseAngle + spin;
                double radialX = Math.cos(angle) * RING_RADIUS;
                double radialY = Math.sin(angle) * RING_RADIUS;
                double shimmer = Math.sin((runes.age + i * 2.0) * 0.21) * 0.009;

                Vec3 p = origin
                    .add(right.scale(radialX))
                    .add(up.scale(radialY + shimmer));

                String glyph = RUNES[(runes.seed + i) % RUNES.length];
                int baseAlpha = runes.stillActive ? 0xE0 : 0xA0;
                int alpha = Mth.clamp((int) (baseAlpha * fade), 0, 255);
                int color = (alpha << 24) | 0x79E9FF;

                poseStack.pushPose();
                poseStack.translate(p.x - cam.x, p.y - cam.y, p.z - cam.z);
                poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
                poseStack.scale(-RUNE_SCALE, -RUNE_SCALE, RUNE_SCALE);

                float w = mc.font.width(glyph) / 2.0f;
                mc.font.drawInBatch(
                    glyph,
                    -w,
                    0.0f,
                    color,
                    false,
                    poseStack.last().pose(),
                    buffers,
                    Font.DisplayMode.SEE_THROUGH,
                    0,
                    FULL_BRIGHT
                );
                poseStack.popPose();
            }
        }

        buffers.endBatch();
    }

    private static final class ActiveRunes {
        private final BlockPos pos;
        private final Direction outward;
        private final int fallbackDurationTicks;
        private final int seed;
        private int age;
        private boolean stillActive;
        private boolean fading;
        private int fadeAge;

        private ActiveRunes(BlockPos pos, Direction outward, int fallbackDurationTicks) {
            this.pos = pos;
            this.outward = outward;
            this.fallbackDurationTicks = Math.max(1, fallbackDurationTicks);
            this.seed = Mth.abs((int) pos.asLong());
            this.age = 0;
            this.stillActive = true;
            this.fading = false;
            this.fadeAge = -1;
        }

        private float fade() {
            if (!this.fading || this.fadeAge < 0) {
                return 1.0f;
            }

            return 1.0f - Mth.clamp(this.fadeAge / (float) FADE_OUT_TICKS, 0.0f, 1.0f);
        }
    }

    private IntentShifterRuneEffects() {
    }
}
