package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.util.Mth;
import org.joml.*;

import java.lang.reflect.Field;

public abstract class Viewport <A extends Viewport<A>> {
    //public final HiZBuffer2 hiZBuffer = new HiZBuffer2();
    public final HiZBuffer hiZBuffer;
    public final DepthFramebuffer depthBoundingBuffer;

    private static final Field planesField;
    static {
        try {
            planesField = FrustumIntersection.class.getDeclaredField("planes");
            planesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public int width;
    public int height;
    public int frameId;
    public Matrix4f vanillaProjection = new Matrix4f();
    public Matrix4f projection = new Matrix4f();
    public Matrix4f modelView = new Matrix4f();
    public final FrustumIntersection frustum = new FrustumIntersection();
    public final Vector4f[] frustumPlanes;
    public double cameraX;
    public double cameraY;
    public double cameraZ;
    public FogParameters fogParameters;

    public final Matrix4f MVP = new Matrix4f();
    public final Vector3i section = new Vector3i();
    public final Vector3f innerTranslation = new Vector3f();

    public final RenderProperties properties;
    public float nearPlane;
    public float farPlane;

    protected Viewport(RenderProperties properties) {
        this(properties, null);
    }

    protected Viewport(RenderProperties properties, RenderBackend backend) {
        Vector4f[] planes = null;
        try {
             planes = (Vector4f[]) planesField.get(this.frustum);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        this.frustumPlanes = planes;

        this.properties = properties;
        this.hiZBuffer = backend == null ? new HiZBuffer(properties) : new HiZBuffer(properties, backend);
        this.depthBoundingBuffer = backend == null ? new DepthFramebuffer() : new DepthFramebuffer(backend);
    }

    public final void delete() {
        this.delete0();
    }

    protected void delete0() {
        this.hiZBuffer.free();
        this.depthBoundingBuffer.free();
    }

    public A setVanillaProjection(Matrix4fc projection) {
        this.vanillaProjection.set(projection);
        return (A) this;
    }

    public A setProjection(Matrix4f projection, float nearPlane, float farPlane) {
        this.projection = projection;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        return (A) this;
    }

    public A setModelView(Matrix4fc modelView) {
        this.modelView.set(modelView);
        return (A) this;
    }

    public A setCamera(double x, double y, double z) {
        this.cameraX = x;
        this.cameraY = y;
        this.cameraZ = z;
        return (A) this;
    }

    public A setScreenSize(int width, int height) {
        this.width = width;
        this.height = height;
        return (A) this;
    }

    public A setFogParameters(FogParameters fogParameters) {
        this.fogParameters = fogParameters;
        return (A) this;
    }

    public A update() {
        //MVP
        this.projection.mul(this.modelView, this.MVP);

        //Update the frustum
        this.frustum.set(this.MVP, false);
        // The traversal uses the first five planes and intentionally omits the far plane.
        // JOML's conventional near/far extraction must follow the active clip range and reverse-Z.
        Vector4f z = new Vector4f(this.MVP.m02(), this.MVP.m12(), this.MVP.m22(), this.MVP.m32());
        Vector4f w = new Vector4f(this.MVP.m03(), this.MVP.m13(), this.MVP.m23(), this.MVP.m33());
        if (this.properties.isReverseZ()) {
            this.frustumPlanes[4].set(w).sub(z);
            this.frustumPlanes[5].set(z);
            if (!this.properties.isZero2One()) this.frustumPlanes[5].add(w);
        } else {
            this.frustumPlanes[4].set(z);
            if (!this.properties.isZero2One()) this.frustumPlanes[4].add(w);
            this.frustumPlanes[5].set(w).sub(z);
        }

        //Translation vectors
        int sx = Mth.floor(this.cameraX)>>5;
        int sy = Mth.floor(this.cameraY)>>5;
        int sz = Mth.floor(this.cameraZ)>>5;
        this.section.set(sx, sy, sz);

        this.innerTranslation.set(
                (float) (this.cameraX-(sx<<5)),
                (float) (this.cameraY-(sy<<5)),
                (float) (this.cameraZ-(sz<<5)));

        if (this.depthBoundingBuffer.resize(this.width, this.height)) {
            this.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
        }

        return (A) this;
    }

    public abstract IGpuBuffer getRenderList();
}
