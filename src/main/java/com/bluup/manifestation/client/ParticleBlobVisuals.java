package com.bluup.manifestation.client;

import com.bluup.manifestation.common.ManifestationNetworking;
import com.bluup.manifestation.server.action.ParticleBlobCodec;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ParticleBlobVisuals {
    private static final List<ActiveBlobCast> ACTIVE = new ArrayList<>();
    private static long NEXT_ID = 1L;

    private ParticleBlobVisuals() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
            ManifestationNetworking.PARTICLE_BLOB_CAST_S2C,
            (client, handler, buf, responseSender) -> {
                String dimensionId = buf.readUtf();
                Vec3 origin = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                byte[] blob = buf.readByteArray();
                int lifetimeTicks = Math.max(1, Math.min(buf.readVarInt(), 200));

                client.execute(() -> {
                    List<ParticleBlobCodec.Point> points;
                    try {
                        points = ParticleBlobCodec.INSTANCE.decode(blob);
                    } catch (Throwable t) {
                        return;
                    }
                    if (points.isEmpty()) {
                        return;
                    }
                    ACTIVE.add(new ActiveBlobCast(NEXT_ID++, dimensionId, origin, points, lifetimeTicks));
                });
            }
        );

        ClientTickEvents.END_CLIENT_TICK.register(ParticleBlobVisuals::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> render(Minecraft.getInstance(), context.matrixStack()));
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.isPaused() || ACTIVE.isEmpty()) {
            return;
        }

        Iterator<ActiveBlobCast> it = ACTIVE.iterator();
        while (it.hasNext()) {
            ActiveBlobCast cast = it.next();
            cast.remainingTicks--;
            cast.phase = (cast.phase + 1) & 0x3fffffff;
            if (cast.remainingTicks < 0) {
                it.remove();
            }
        }
    }

    private static void render(Minecraft mc, PoseStack poseStack) {
        if (mc.level == null || mc.player == null || ACTIVE.isEmpty()) {
            return;
        }

        String currentDimension = mc.level.dimension().location().toString();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        var lookF = mc.gameRenderer.getMainCamera().getLookVector();
        Vec3 look = new Vec3(lookF.x(), lookF.y(), lookF.z()).normalize();
        Vec3 right = look.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0e-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(look).normalize();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lineBuffer = buffers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        try {
            for (ActiveBlobCast cast : ACTIVE) {
                if (!currentDimension.equals(cast.dimensionId)) {
                    continue;
                }

                double dist = cast.origin.distanceTo(camera);
                int step = computeSampleStep(cast.points.size(), dist);
                int phase = cast.phase % step;
                double pointHalfSize = computePointHalfSize(dist);
                float alpha = computeAlpha(dist);

                for (int i = 0; i < cast.points.size(); i++) {
                    if ((i + phase) % step != 0) {
                        continue;
                    }

                    ParticleBlobCodec.Point point = cast.points.get(i);
                    Vec3 p = cast.origin.add(point.getOffset());
                    Vec3 c = point.getColor();

                    Vec3 rx = right.scale(pointHalfSize);
                    Vec3 uy = up.scale(pointHalfSize);
                    drawLineSegment(poseStack, lineBuffer, p.subtract(rx), p.add(rx), (float) c.x, (float) c.y, (float) c.z, alpha);
                    drawLineSegment(poseStack, lineBuffer, p.subtract(uy), p.add(uy), (float) c.x, (float) c.y, (float) c.z, alpha);
                }
            }
        } finally {
            poseStack.popPose();
            buffers.endBatch(RenderType.lines());
        }
    }

    private static int computeSampleStep(int pointCount, double distance) {
        int targetVisible = 850;
        int step = Math.max(1, (pointCount + targetVisible - 1) / targetVisible);
        if (distance > 24.0) {
            step *= 2;
        }
        if (distance > 48.0) {
            step *= 2;
        }
        return Math.max(1, step);
    }

    private static double computePointHalfSize(double distance) {
        if (distance < 12.0) {
            return 0.032;
        }
        if (distance < 28.0) {
            return 0.024;
        }
        return 0.018;
    }

    private static float computeAlpha(double distance) {
        if (distance < 20.0) {
            return 0.92f;
        }
        if (distance < 42.0) {
            return 0.75f;
        }
        return 0.58f;
    }

    private static void drawLineSegment(
        PoseStack poseStack,
        VertexConsumer lineBuffer,
        Vec3 from,
        Vec3 to,
        float r,
        float g,
        float b,
        float alpha
    ) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len <= 1.0e-8) {
            return;
        }

        Vec3 normal = delta.scale(1.0 / len);
        var pose = poseStack.last();
        lineBuffer.vertex(pose.pose(), (float) from.x, (float) from.y, (float) from.z)
            .color(r, g, b, alpha)
            .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z)
            .endVertex();
        lineBuffer.vertex(pose.pose(), (float) to.x, (float) to.y, (float) to.z)
            .color(r, g, b, alpha)
            .normal(pose.normal(), (float) normal.x, (float) normal.y, (float) normal.z)
            .endVertex();
    }

    private static final class ActiveBlobCast {
        final long id;
        final String dimensionId;
        final Vec3 origin;
        final List<ParticleBlobCodec.Point> points;
        int remainingTicks;
        int phase;

        private ActiveBlobCast(long id, String dimensionId, Vec3 origin, List<ParticleBlobCodec.Point> points, int remainingTicks) {
            this.id = id;
            this.dimensionId = dimensionId;
            this.origin = origin;
            this.points = points;
            this.remainingTicks = remainingTicks;
            this.phase = (int) (id % 17L);
        }
    }
}