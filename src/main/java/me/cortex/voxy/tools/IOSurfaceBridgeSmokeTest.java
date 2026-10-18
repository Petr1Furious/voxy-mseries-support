package me.cortex.voxy.tools;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.metal.MetalNative;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;

/**
 * M10 validation: IOSurface-backed Metal texture, allocate + wrap + introspect.
 *
 * This smoke test only exercises the Metal side of the bridge: we allocate
 * an IOSurface, wrap it as an MTLTexture, and verify the dimensions /
 * handles come back sane. The GL roundtrip (binding the same IOSurface as a
 * GL texture via CGLTexImageIOSurface2D and sampling it from MC's compositor)
 * lives in a future smoke test that runs inside an active GL context — the
 * Metal-only path doesn't have one available here.
 *
 * Successfully running this proves the JNI/lifetime plumbing is right,
 * which is the structural prerequisite for the full MC↔Voxy compositing
 * the IOSurface bridge will eventually enable.
 */
public final class IOSurfaceBridgeSmokeTest {

    private static final int W = 256;
    private static final int H = 256;

    private IOSurfaceBridgeSmokeTest() {}

    public static void main(String[] args) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!(os.contains("mac") && arch.contains("aarch64"))) {
            System.err.println("IOSurface bridge smoke test requires macOS aarch64 (got " + os + "/" + arch + ")");
            System.exit(2);
        }
        if (!MetalNative.load()) {
            System.err.println("Metal native library failed to load");
            System.exit(2);
        }

        MetalRenderBackend backend = new MetalRenderBackend();
        IOSurfaceBridge bridge = null;
        try {
            long device = backend.device();
            if (device == 0) throw new RuntimeException("MetalRenderBackend.device() returned 0");

            bridge = IOSurfaceBridge.create(device, W, H, IOSurfaceBridge.IOSurfaceFormat.BGRA8);

            int actualW = MetalNative.iosurfaceGetWidth(bridge.ioSurfaceHandle());
            int actualH = MetalNative.iosurfaceGetHeight(bridge.ioSurfaceHandle());
            int bpr     = MetalNative.iosurfaceGetBytesPerRow(bridge.ioSurfaceHandle());

            System.out.printf("IOSurface     : handle=0x%016X  %dx%d  bytesPerRow=%d%n",
                    bridge.ioSurfaceHandle(), actualW, actualH, bpr);
            System.out.printf("MTLTexture    : handle=0x%016X  format=%s%n",
                    bridge.metalTextureHandle(), bridge.format());

            if (actualW != W || actualH != H) {
                throw new RuntimeException("IOSurface dimensions mismatch: got " + actualW + "x" + actualH);
            }
            if (bpr < W * 4) {
                throw new RuntimeException("IOSurface bytesPerRow=" + bpr + " < " + (W * 4));
            }
            if (bridge.metalTextureHandle() == 0) {
                throw new RuntimeException("MTLTexture handle is 0");
            }
            if (bridge.ioSurfaceHandle() == 0) {
                throw new RuntimeException("IOSurface handle is 0");
            }

            // Second leg: render a clear color through the encoder against the
            // IOSurface-backed texture. If this succeeds we know the bridge is
            // usable as an actual render target (not just a metadata wrapper) —
            // the prerequisite for AbstractRenderPipeline.runPipeline routing
            // Voxy's output through the bridge.
            float clearR = 0.20f, clearG = 0.60f, clearB = 0.90f;
            var pass = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(W, H)
                    .clearColor(bridge.asGpuTexture(), clearR, clearG, clearB, 1.0f)
                    .build();
            try (var enc = backend.beginRenderPass(pass)) {
                // No draws — the clear load action is enough to write all pixels.
            }
            backend.finish();

            byte[] pixels = backend.readPixelsRGBA8(bridge.asGpuTexture(), 0, 0, W, H);
            int got = ((pixels[0] & 0xFF) << 24) | ((pixels[1] & 0xFF) << 16)
                    | ((pixels[2] & 0xFF) << 8) | (pixels[3] & 0xFF);
            // readPixelsRGBA8 converts the IOSurface's BGRA8 storage to RGBA.
            // Verify the raw native storage separately below.
            // Allow ±2 tolerance per channel for float→unorm8 rounding mode
            // differences (Metal rounds to nearest with ties-to-even; our
            // Java side uses round-half-up).
            int gotB = (got >>> 8) & 0xFF;
            int gotG = (got >>> 16) & 0xFF;
            int gotR = (got >>> 24) & 0xFF;
            int gotA = (got >>>  0) & 0xFF;
            int expectB = Math.round(clearB * 255);
            int expectG = Math.round(clearG * 255);
            int expectR = Math.round(clearR * 255);
            var raw = backend.createBuffer((long) W * H * 4);
            try {
                backend.copyTextureToBuffer(bridge.asGpuTexture(), raw, W, H, 0);
                backend.finish();
                long ptr = ((me.cortex.voxy.client.core.metal.MetalBuffer) raw).getContentsPtr();
                int rawB = org.lwjgl.system.MemoryUtil.memGetByte(ptr) & 255;
                int rawR = org.lwjgl.system.MemoryUtil.memGetByte(ptr + 2) & 255;
                if (Math.abs(rawB - expectB) > 2 || Math.abs(rawR - expectR) > 2) throw new AssertionError("Raw IOSurface storage is not BGRA");
            } finally { raw.free(); }
            System.out.printf("First pixel   : B=0x%02X G=0x%02X R=0x%02X A=0x%02X (expected B=0x%02X G=0x%02X R=0x%02X)%n",
                    gotB, gotG, gotR, gotA, expectB, expectG, expectR);
            int tol = 2;
            if (Math.abs(gotB - expectB) > tol || Math.abs(gotG - expectG) > tol
                    || Math.abs(gotR - expectR) > tol || gotA != 0xFF) {
                throw new RuntimeException("IOSurface render pass produced wrong clear color");
            }

            System.out.println();
            System.out.println("M10 SMOKE OK — IOSurface-backed MTLTexture rendered + read back on Apple Silicon");
        } finally {
            if (bridge != null) bridge.close();
            backend.shutdown();
        }
    }
}
