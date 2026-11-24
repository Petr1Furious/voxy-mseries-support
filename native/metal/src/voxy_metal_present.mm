// voxy_metal_present.mm — presents Minecraft's finished frame through a CAMetalLayer
// over the window, instead of OpenGL's default framebuffer.

#import "voxy_metal.h"
#import <AppKit/AppKit.h>
#import <QuartzCore/QuartzCore.h>

static void voxy_on_main(void (^block)(void)) {
    if ([NSThread isMainThread]) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}

static void voxy_fit_layer(CAMetalLayer *layer, NSWindow *window) {
    NSView *view = window.contentView;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];
    layer.frame = view.bounds;
    layer.contentsScale = window.backingScaleFactor;
    [CATransaction commit];
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlPresentLayerCreate(
        JNIEnv *, jclass, jlong deviceHandle, jlong windowHandle) {
    @autoreleasepool {
        if (deviceHandle == 0 || windowHandle == 0) return 0;
        id<MTLDevice> device = voxy_handle_cast<id<MTLDevice>>(deviceHandle);
        NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
        __block CAMetalLayer *layer = nil;
        voxy_on_main(^{
            NSView *view = window.contentView;
            view.wantsLayer = YES;
            layer = [CAMetalLayer layer];
            layer.device = device;
            layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
            layer.framebufferOnly = NO;
            layer.opaque = YES;
            layer.zPosition = 1e6;
            layer.autoresizingMask = kCALayerWidthSizable | kCALayerHeightSizable;
            voxy_fit_layer(layer, window);
            [view.layer addSublayer:layer];
        });
        return voxy_handle_from(layer);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlPresentLayerConfigure(
        JNIEnv *, jclass, jlong layerHandle, jlong windowHandle, jint width, jint height, jboolean vsync) {
    @autoreleasepool {
        if (layerHandle == 0) return;
        CAMetalLayer *layer = voxy_handle_cast<CAMetalLayer *>(layerHandle);
        NSWindow *window = (__bridge NSWindow *)(void *)windowHandle;
        voxy_on_main(^{
            voxy_fit_layer(layer, window);
            layer.drawableSize = CGSizeMake(width, height);
            layer.displaySyncEnabled = vsync;
        });
    }
}

// Copies `source` into the next drawable and presents it. Returns the retained
// command buffer (to know when `source` may be rewritten), or 0 without a drawable.
extern "C" JNIEXPORT jlong JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlPresentLayerPresent(
        JNIEnv *, jclass, jlong layerHandle, jlong queueHandle, jlong sourceHandle) {
    @autoreleasepool {
        if (layerHandle == 0 || queueHandle == 0 || sourceHandle == 0) return 0;
        CAMetalLayer *layer = voxy_handle_cast<CAMetalLayer *>(layerHandle);
        id<MTLCommandQueue> queue = voxy_handle_cast<id<MTLCommandQueue>>(queueHandle);
        id<MTLTexture> source = voxy_handle_cast<id<MTLTexture>>(sourceHandle);
        id<CAMetalDrawable> drawable = [layer nextDrawable];
        if (drawable == nil) return 0;
        id<MTLTexture> target = drawable.texture;
        id<MTLCommandBuffer> cb = [queue commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        NSUInteger w = MIN(source.width, target.width), h = MIN(source.height, target.height);
        [blit copyFromTexture:source sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                   sourceSize:MTLSizeMake(w, h, 1) toTexture:target destinationSlice:0
             destinationLevel:0 destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blit endEncoding];
        [cb presentDrawable:drawable];
        [cb commit];
        return voxy_handle_from(cb);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_metal_MetalNative_mtlPresentLayerDestroy(
        JNIEnv *, jclass, jlong layerHandle) {
    @autoreleasepool {
        if (layerHandle == 0) return;
        CAMetalLayer *layer = voxy_handle_cast<CAMetalLayer *>(layerHandle);
        voxy_on_main(^{ [layer removeFromSuperlayer]; });
        voxy_release(layerHandle);
    }
}
