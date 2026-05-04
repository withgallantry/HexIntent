package com.bluup.manifestation.client;

import com.bluup.manifestation.Manifestation;
import com.bluup.manifestation.common.ManifestationNetworking;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class SplinterVisuals {
    private static final Map<UUID, SplinterVisualState> ACTIVE = new HashMap<>();
    private static boolean renderFailureLogged;

    private static final float GLYPH_SCALE = 0.018f;
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final String[] RING_RUNES = {"ᚲ", "ᚱ", "ᛟ", "ᚹ", "ᚨ", "ᚦ"};
    private static final double MAX_MATCH_HOP_BLOCKS = 18.0;
    private static final double MAX_MATCH_HOP_SQ = MAX_MATCH_HOP_BLOCKS * MAX_MATCH_HOP_BLOCKS;
    private static final double SHORT_HOP_BLOCKS = 2.75;
    private static final int INTERP_TICKS_SHORT_HOP = 2;
    private static final int INTERP_TICKS_LONG_HOP = 1;
    private static final float INTERP_LOOKAHEAD_PARTIAL = 0.65f;

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.SPLINTER_SNAPSHOT_S2C,
            (client, handler, buf, responseSender) -> {
                int count = buf.readVarInt();
                Map<UUID, SplinterVisual> snapshot = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    UUID id = buf.readUUID();
                    String dimensionId = buf.readUtf();
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    double z = buf.readDouble();
                    long castAtGameTime = buf.readLong();
                    snapshot.put(id, new SplinterVisual(id, dimensionId, new Vec3(x, y, z), castAtGameTime));
                }

                client.execute(() -> {
                    long now = client.level != null ? client.level.getGameTime() : 0L;
                    Map<UUID, SplinterVisualState> previous = new HashMap<>(ACTIVE);
                    Map<UUID, SplinterVisualState> next = new HashMap<>();
                    java.util.Set<UUID> consumedPrevious = new java.util.HashSet<>();

                    for (SplinterVisual incoming : snapshot.values()) {
                        SplinterVisualState direct = previous.get(incoming.id);
                        SplinterVisualState matched = direct;

                        if (matched == null) {
                            matched = findNearestPrevious(previous, consumedPrevious, incoming, now);
                        }

                        Vec3 renderPos;
                        long interpStartTick = now;
                        int interpTicks = 0;
                        if (matched != null) {
                            consumedPrevious.add(matched.id);
                            Vec3 priorPos = matched.renderPosition(now, INTERP_LOOKAHEAD_PARTIAL);
                            double hopSq = priorPos.distanceToSqr(incoming.position);
                            if (hopSq > 1.0e-8) {
                                renderPos = priorPos;
                                interpTicks = interpolationTicksFor(Math.sqrt(hopSq));
                            } else {
                                renderPos = incoming.position;
                            }
                        } else {
                            renderPos = incoming.position;
                        }

                        next.put(
                            incoming.id,
                            new SplinterVisualState(
                                incoming.id,
                                incoming.dimensionId,
                                renderPos,
                                incoming.position,
                                interpStartTick,
                                interpTicks,
                                incoming.castAtGameTime
                            )
                        );
                    }

                    ACTIVE.clear();
                    ACTIVE.putAll(next);
                });
            }
        );

        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> render(Minecraft.getInstance(), context.matrixStack()));
    }

    private static void render(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return;
        }

        String dimensionId = mc.level.dimension().location().toString();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long gameTime = mc.level.getGameTime();

        try {
            Iterator<SplinterVisualState> it = ACTIVE.values().iterator();
            while (it.hasNext()) {
                SplinterVisualState splinter = it.next();
                if (!dimensionId.equals(splinter.dimensionId)) {
                    continue;
                }

                Vec3 renderPos = splinter.renderPosition(gameTime, mc.getFrameTime());

                if (mc.player.position().distanceToSqr(renderPos) > 96.0 * 96.0) {
                    continue;
                }

                float urgency = Mth.clamp((splinter.castAtGameTime - gameTime) / 40.0f, 0.0f, 1.0f);
                float pulse = 0.72f + (0.28f * Mth.sin((gameTime + mc.getFrameTime()) * 0.20f));
                float alpha = (0.55f + 0.45f * pulse) * (0.7f + 0.3f * urgency);

                Vec3 top = renderPos.add(0.0, 1.55, 0.0);
                Vec3 mid = renderPos.add(0.0, 1.25, 0.0);
                Vec3 base = renderPos.add(0.0, 0.98, 0.0);

                drawGlyph(mc, poseStack, buffers, "◆", top, camera, (int) (alpha * 220.0f), 0x78f2ff, 1.05f);
                drawGlyph(mc, poseStack, buffers, "◇", mid, camera, (int) (alpha * 180.0f), 0x52c8ff, 1.35f);
                drawGlyph(mc, poseStack, buffers, "◆", base, camera, (int) (alpha * 160.0f), 0x2f8fdb, 0.85f);

                // A tiny rune ring nods to Greater Sentinel visuals while keeping this clearly smaller.
                for (int i = 0; i < RING_RUNES.length; i++) {
                    double angle = ((Math.PI * 2.0) / RING_RUNES.length) * i + (gameTime * 0.03);
                    Vec3 runePos = renderPos
                        .add(Math.cos(angle) * 0.28, 1.18 + Math.sin((gameTime + i * 3) * 0.09) * 0.02, Math.sin(angle) * 0.28);
                    drawGlyph(mc, poseStack, buffers, RING_RUNES[i], runePos, camera, (int) (alpha * 110.0f), 0x6ee6ff, 0.55f);
                }
            }
        } catch (Throwable t) {
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                Manifestation.LOGGER.warn("Manifestation: failed rendering splinter visuals", t);
            }
        } finally {
            buffers.endBatch();
        }
    }

    private static void drawGlyph(
        Minecraft mc,
        PoseStack poseStack,
        MultiBufferSource.BufferSource buffers,
        String glyph,
        Vec3 worldPos,
        Vec3 camera,
        int alpha,
        int rgb,
        float scaleMultiplier
    ) {
        int clampedAlpha = Mth.clamp(alpha, 0, 255);
        int color = (clampedAlpha << 24) | (rgb & 0xFFFFFF);

        poseStack.pushPose();
        try {
            poseStack.translate(worldPos.x - camera.x, worldPos.y - camera.y, worldPos.z - camera.z);
            poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            float scale = GLYPH_SCALE * scaleMultiplier;
            poseStack.scale(-scale, -scale, scale);

            Font font = mc.font;
            float halfWidth = font.width(glyph) / 2.0f;
            font.drawInBatch(
                glyph,
                -halfWidth,
                0.0f,
                color,
                false,
                poseStack.last().pose(),
                buffers,
                Font.DisplayMode.SEE_THROUGH,
                0,
                FULL_BRIGHT
            );
        } finally {
            poseStack.popPose();
        }
    }

    private static SplinterVisualState findNearestPrevious(
        Map<UUID, SplinterVisualState> previous,
        java.util.Set<UUID> consumedPrevious,
        SplinterVisual incoming,
        long now
    ) {
        SplinterVisualState best = null;
        double bestSq = MAX_MATCH_HOP_SQ;

        for (SplinterVisualState candidate : previous.values()) {
            if (consumedPrevious.contains(candidate.id)) {
                continue;
            }
            if (!incoming.dimensionId.equals(candidate.dimensionId)) {
                continue;
            }

            Vec3 candidatePos = candidate.renderPosition(now, 0.0f);
            double sq = candidatePos.distanceToSqr(incoming.position);
            if (sq <= bestSq) {
                bestSq = sq;
                best = candidate;
            }
        }

        return best;
    }

    private static int interpolationTicksFor(double hopDist) {
        if (hopDist <= SHORT_HOP_BLOCKS) {
            return INTERP_TICKS_SHORT_HOP;
        }
        return INTERP_TICKS_LONG_HOP;
    }

    private record SplinterVisual(UUID id, String dimensionId, Vec3 position, long castAtGameTime) {
    }

    private static final class SplinterVisualState {
        private final UUID id;
        private final String dimensionId;
        private final Vec3 from;
        private final Vec3 to;
        private final long startTick;
        private final int interpTicks;
        private final long castAtGameTime;

        private SplinterVisualState(UUID id, String dimensionId, Vec3 from, Vec3 to, long startTick, int interpTicks, long castAtGameTime) {
            this.id = id;
            this.dimensionId = dimensionId;
            this.from = from;
            this.to = to;
            this.startTick = startTick;
            this.interpTicks = interpTicks;
            this.castAtGameTime = castAtGameTime;
        }

        private Vec3 renderPosition(long gameTime, float partialTick) {
            if (interpTicks <= 0) {
                return to;
            }
            float elapsed = (float) (gameTime - startTick) + partialTick;
            float linear = Mth.clamp(elapsed / (float) interpTicks, 0.0f, 1.0f);
            float t = linear * linear * (3.0f - 2.0f * linear);
            return new Vec3(
                Mth.lerp(t, from.x, to.x),
                Mth.lerp(t, from.y, to.y),
                Mth.lerp(t, from.z, to.z)
            );
        }
    }

    private SplinterVisuals() {
    }
}
