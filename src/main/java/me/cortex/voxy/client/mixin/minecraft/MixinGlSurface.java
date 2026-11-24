package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.opengl.GlSurface;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.voxy.client.core.metal.MetalPresenter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlSurface.class)
public class MixinGlSurface {
    @Shadow @Final private long windowHandle;

    @Inject(method = "configure", at = @At("TAIL"))
    private void voxy$configureMetal(GpuSurface.Configuration config, CallbackInfo ci) {
        var presenter = MetalPresenter.get(this.windowHandle);
        if (presenter != null) presenter.configure(config.width(), config.height(), config.presentMode() == GpuSurface.PresentMode.FIFO);
    }

    @Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
    private void voxy$blitMetal(CommandEncoderBackend encoder, GpuTextureView view, CallbackInfo ci) {
        var presenter = MetalPresenter.get(this.windowHandle);
        if (presenter == null) return;
        presenter.blit(((GlTexture) view.texture()).glId(), view.getWidth(0), view.getHeight(0));
        ci.cancel();
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private void voxy$presentMetal(CallbackInfo ci) {
        var presenter = MetalPresenter.get(this.windowHandle);
        if (presenter == null) return;
        presenter.present();
        ci.cancel();
    }
}
