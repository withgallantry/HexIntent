package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class ConstellationVisuals {
    public static class Star {
        public final double x, y, z;
        public Star(double x, double y, double z) {
            this.x = x; this.y = y; this.z = z;
        }
    }
    public static class Edge {
        public final int from, to;
        public Edge(int from, int to) {
            this.from = from; this.to = to;
        }
    }
    public static class Constellation {
        public final UUID owner;
        public final int color;
        public final List<Star> stars;
        public final List<Edge> edges;
        public Constellation(UUID owner, int color, List<Star> stars, List<Edge> edges) {
            this.owner = owner;
            this.color = color;
            this.stars = stars;
            this.edges = edges;
        }
    }

    private static final Map<UUID, Constellation> ACTIVE = new ConcurrentHashMap<>();

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.CONSTELLATION_SNAPSHOT_S2C,
            (client, handler, buf, responseSender) -> {
                int count = buf.readVarInt();
                Map<UUID, Constellation> snapshot = new HashMap<>();
                for (int i = 0; i < count; i++) {
                    UUID owner = buf.readUUID();
                    int color = buf.readInt();
                    int starCount = buf.readVarInt();
                    List<Star> stars = new ArrayList<>();
                    for (int s = 0; s < starCount; s++) {
                        double x = buf.readDouble();
                        double y = buf.readDouble();
                        double z = buf.readDouble();
                        stars.add(new Star(x, y, z));
                    }
                    int edgeCount = buf.readVarInt();
                    List<Edge> edges = new ArrayList<>();
                    for (int e = 0; e < edgeCount; e++) {
                        int from = buf.readVarInt();
                        int to = buf.readVarInt();
                        edges.add(new Edge(from, to));
                    }
                    snapshot.put(owner, new Constellation(owner, color, stars, edges));
                }
                client.execute(() -> {
                    ACTIVE.clear();
                    ACTIVE.putAll(snapshot);
                });
            }
        );
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> render(Minecraft.getInstance(), ctx.matrixStack()));
    }

    private static void render(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) return;
        // Only render at night
        float day = mc.level.getDayTime() % 24000L;
        if (day < 13000 || day > 23000) return;
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Constellation c : ACTIVE.values()) {
            int rgb = c.color & 0xFFFFFF;
            float r = ((rgb >> 16) & 0xFF) / 255.0f;
            float g = ((rgb >> 8) & 0xFF) / 255.0f;
            float b = (rgb & 0xFF) / 255.0f;
            // Draw edges
            for (Edge e : c.edges) {
                if (e.from < 0 || e.from >= c.stars.size() || e.to < 0 || e.to >= c.stars.size()) continue;
                Star a = c.stars.get(e.from);
                Star b_ = c.stars.get(e.to);
                drawLine(poseStack, buffers, a, b_, camera, r, g, b, 0.85f);
            }
            // Draw stars
            for (Star s : c.stars) {
                drawStar(poseStack, buffers, s, camera, r, g, b, 1.0f);
            }
        }
        buffers.endBatch();
    }

    private static void drawLine(PoseStack poseStack, MultiBufferSource buffers, Star a, Star b, Vec3 camera, float r, float g, float b_, float alpha) {
        var buf = buffers.getBuffer(RenderType.LINES);
        double skyRadius = 120.0;
        // Project unit sphere to sky dome
        double ax = a.x * skyRadius, ay = a.y * skyRadius + 90.0, az = a.z * skyRadius;
        double bx = b.x * skyRadius, by = b.y * skyRadius + 90.0, bz = b.z * skyRadius;
        poseStack.pushPose();
        try {
            buf.vertex(poseStack.last().pose(), (float)(ax - camera.x), (float)(ay - camera.y), (float)(az - camera.z)).color(r, g, b_, alpha).endVertex();
            buf.vertex(poseStack.last().pose(), (float)(bx - camera.x), (float)(by - camera.y), (float)(bz - camera.z)).color(r, g, b_, alpha).endVertex();
        } finally {
            poseStack.popPose();
        }
    }

    private static void drawStar(PoseStack poseStack, MultiBufferSource buffers, Star s, Vec3 camera, float r, float g, float b_, float alpha) {
        var buf = buffers.getBuffer(RenderType.LINES);
        double skyRadius = 120.0;
        double x = s.x * skyRadius, y = s.y * skyRadius + 90.0, z = s.z * skyRadius;
        float size = 1.2f;
        poseStack.pushPose();
        try {
            // Simple cross
            buf.vertex(poseStack.last().pose(), (float)(x - camera.x - size), (float)(y - camera.y), (float)(z - camera.z)).color(r, g, b_, alpha).endVertex();
            buf.vertex(poseStack.last().pose(), (float)(x - camera.x + size), (float)(y - camera.y), (float)(z - camera.z)).color(r, g, b_, alpha).endVertex();
            buf.vertex(poseStack.last().pose(), (float)(x - camera.x), (float)(y - camera.y - size), (float)(z - camera.z)).color(r, g, b_, alpha).endVertex();
            buf.vertex(poseStack.last().pose(), (float)(x - camera.x), (float)(y - camera.y + size), (float)(z - camera.z)).color(r, g, b_, alpha).endVertex();
        } finally {
            poseStack.popPose();
        }
    }
}
