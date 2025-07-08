package com.bouncingelf10.animatedLogo.mixin;

import com.bouncingelf10.animatedLogo.AnimatedLogo;
import com.bouncingelf10.animatedLogo.DarkLoadingScreenCompat;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SplashOverlay;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.sound.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceReload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static com.bouncingelf10.animatedLogo.AnimatedLogo.LOGGER;
import static net.minecraft.client.gui.DrawableHelper.drawTexture;
import static net.minecraft.client.gui.DrawableHelper.fill;

@Mixin(SplashOverlay.class)
@SuppressWarnings({"unused", "FieldMayBeFinal"})
public class SplashOverlayMixin {
    @Mutable
    @Shadow @Final private ResourceReload reload;
    @Shadow private float progress;

    @Unique private int count = 0;
    @Unique private Identifier[] frames;
    @Unique private boolean inited = false;
    @Unique private static final int FRAMES = 12;
    @Unique private static final int IMAGE_PER_FRAME = 4;
    @Unique private static final int FRAMES_PER_FRAME = 2;
    @Unique private float f = 0;
    @Unique private boolean animationDone = false;

    @Shadow
    @Final
    private static IntSupplier BRAND_ARGB; // Color of background
    @Unique
    private static int whiteARGB = ColorHelper.Argb.getArgb(255, 255, 255, 255);

    @Unique
    private static IntSupplier LOADING_FILL = () ->
            applyAlphaToColor(DarkLoadingScreenCompat.getBarColor(whiteARGB), 1.0f);
    @Unique
    private static IntSupplier LOADING_BORDER = () ->
            applyAlphaToColor(DarkLoadingScreenCompat.getBorderColor(whiteARGB), 1.0f);

    @Unique
    private static IntSupplier TEXT_COLOR = () ->
            applyAlphaToColor(DarkLoadingScreenCompat.getLogoColor(whiteARGB), 1.0f);


    @Unique private boolean soundPlayed = false;
    @Unique private boolean animationReady = false;
    @Unique private boolean isFadingOut = false;
    @Unique private boolean isFadingFinished = false;

    @Unique private long animationStartTime = -1;
    @Unique private static final float TOTAL_ANIMATION_DURATION = 3.0f; // in seconds
    @Unique private long animationDelayStartTime = -1;
    @Unique private static final long ANIMATION_DELAY_MS = 1;
    @Unique private long fadeOutStartTime = -1;
    @Unique private static final long FADE_OUT_DURATION_MS = 1000; // in milliseconds
    @Unique private static float loadingBarProgress = 0.0f; // in seconds

    // Draw vanilla loading bar
    // Copied from: net.minecraft.client.gui.screen.SplashOverlay.renderProgressBar
    @Unique
    private void drawLoadingBar(MatrixStack matrices, float opacity, float progress) {
        int screenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int screenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();

        int centerX = screenWidth / 2;
        int progressBarY = (int)(screenHeight * 0.8325);

        double logoHeight = Math.min(screenWidth * 0.75, screenHeight) * 0.25;
        double logoWidth = logoHeight * 4.0;
        int halfLogoWidth = (int)(logoWidth * 0.5);

        int minX = centerX - halfLogoWidth;
        int maxX = centerX + halfLogoWidth;
        int minY = progressBarY - 5;
        int maxY = progressBarY + 5;

        int filled = MathHelper.ceil((float)(maxX - minX - 2) * progress);
        int colorFilled = LOADING_FILL.getAsInt();
        int colorOutline = LOADING_BORDER.getAsInt();

        fill(matrices, minX + 2, minY + 2, minX + filled, maxY - 2, colorFilled);
        fill(matrices, minX + 1, minY, maxX - 1, minY + 1, colorOutline);
        fill(matrices, minX + 1, maxY, maxX - 1, maxY - 1, colorOutline);
        fill(matrices, minX, minY, minX + 1, maxY, colorOutline);
        fill(matrices, maxX, minY, maxX - 1, maxY, colorOutline);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(MinecraftClient client, ResourceReload monitor, Consumer<Throwable> exceptionHandler, boolean reloading, CallbackInfo ci) {
        animationDelayStartTime = System.currentTimeMillis();
    }

    // Stop rendering of title
    @ModifyArg(method = "render",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderColor(FFFF)V", ordinal = 0),
            index = 3)
    private float removeText(float red) {
        return 0;
    }

    // Stop rendering of loading bar
    @ModifyArg(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/SplashOverlay;renderProgressBar(Lnet/minecraft/client/util/math/MatrixStack;IIIIF)V", ordinal = 0),
            index = 5)
    private float removeBar(float opacity) {
        return 0.0f;
    }


    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void preRender(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        long elapsed = System.currentTimeMillis() - animationDelayStartTime;

        if (elapsed < ANIMATION_DELAY_MS) {
            fill(matrices, 0, 0,
                    MinecraftClient.getInstance().getWindow().getScaledWidth(), MinecraftClient.getInstance().getWindow().getScaledHeight(),
                    withAlpha((int)((elapsed * 255) / ANIMATION_DELAY_MS / 10),
                            applyAlphaToColor(BRAND_ARGB.getAsInt(), 1.0f)));
            ci.cancel();
            return;
        }

        if (!animationDone) {
            drawAnimatedIntro(matrices);
            ci.cancel();
        }
    }

    @Unique
    private void drawAnimatedIntro(MatrixStack matrices) {
        if (!reload.isComplete() && !isFadingOut && !isFadingFinished) {

            fill(matrices, 0, 0,
                    MinecraftClient.getInstance().getWindow().getScaledWidth(), MinecraftClient.getInstance().getWindow().getScaledHeight(),
                    applyAlphaToColor(BRAND_ARGB.getAsInt(), 1.0f));

            drawLoadingBar(matrices, 1.0f, Math.max(loadingBarProgress, reload.getProgress()));
            loadingBarProgress = reload.getProgress();

            return;
        }

        if (reload.isComplete() && !isFadingOut && !isFadingFinished) {
            isFadingOut = true;
            fadeOutStartTime = System.currentTimeMillis();
        }

        if (isFadingOut && !isFadingFinished) {
            long elapsedFade = System.currentTimeMillis() - fadeOutStartTime;
            float fadeFactor = 1.0f - MathHelper.clamp((float)elapsedFade / FADE_OUT_DURATION_MS, 0.0f, 1.0f);

            fill(matrices, 0, 0,
                    MinecraftClient.getInstance().getWindow().getScaledWidth(), MinecraftClient.getInstance().getWindow().getScaledHeight(),
                    applyAlphaToColor(BRAND_ARGB.getAsInt(), 1.0f));

            drawLoadingBar(matrices, fadeFactor, Math.max(loadingBarProgress, reload.getProgress()));
            loadingBarProgress = reload.getProgress();

            if (fadeFactor <= 0.0) {
                isFadingFinished = true;
            }

            return;
        }

        if (isFadingFinished && !animationReady) {
            animationReady = true;
            animationStartTime = System.nanoTime();

            if (!soundPlayed) {
                MinecraftClient.getInstance().getSoundManager().play(
                        PositionedSoundInstance.master(AnimatedLogo.STARTUP_SOUND_EVENT, 1.0F)
                );
                LOGGER.info("Playing startup sound");
                soundPlayed = true;
            }

            if (!inited) {
                this.frames = new Identifier[FRAMES];
                for (int i = 0; i < FRAMES; i++) {
                    this.frames[i] = Identifier.of("animated-mojang-logo", "textures/gui/frame_" + i + ".png");
                }
                inited = true;
            }
        }

        if (animationReady) {
            double elapsedSeconds = (System.nanoTime() - animationStartTime) / 1_000_000_000.0;
            double animationProgress = Math.min(elapsedSeconds / TOTAL_ANIMATION_DURATION, 1.0);

            int totalFrameCount = FRAMES * IMAGE_PER_FRAME * FRAMES_PER_FRAME;
            count = (int)(animationProgress * totalFrameCount);

            if (animationProgress >= 1.0) {
                animationDone = true;
                count = totalFrameCount - 1;
            }

            int screenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
            int screenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();
            int width = screenWidth / 2;
            int height = width * 256 / 1024;
            int x = (screenWidth - width) / 2;
            int y = (screenHeight - height) / 2;

            int frameIndex = count / IMAGE_PER_FRAME / FRAMES_PER_FRAME;
            int subFrameY = 256 * ((count % (IMAGE_PER_FRAME * FRAMES_PER_FRAME)) / FRAMES_PER_FRAME);

            fill(matrices, 0, 0,
                    MinecraftClient.getInstance().getWindow().getScaledWidth(), MinecraftClient.getInstance().getWindow().getScaledHeight(),
                    applyAlphaToColor(BRAND_ARGB.getAsInt(), 1.0f));

            RenderSystem.setShaderTexture(0, frames[frameIndex]);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(
                    ((applyAlphaToColor(TEXT_COLOR.getAsInt(), 1.0f) >> 16) & 0xFF) / 255.0f,
                    ((applyAlphaToColor(TEXT_COLOR.getAsInt(), 1.0f) >> 8) & 0xFF) / 255.0f,
                    (applyAlphaToColor(TEXT_COLOR.getAsInt(), 1.0f) & 0xFF) / 255.0f,
                    ((applyAlphaToColor(TEXT_COLOR.getAsInt(), 1.0f) >> 24) & 0xFF) / 255.0f
            );

            drawTexture(matrices, x, y, width, height, 0, subFrameY, 1024, 256, 1024, 1024);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screen/SplashOverlay;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIFFIIII)V",
                    shift = At.Shift.AFTER,
                    ordinal = 1
            ) // this had me scraching my head for a while T_T
    )
    private void onAfterRenderLogo(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci,
                                   @Local(ordinal = 2) int scaledWidth, @Local(ordinal = 3) int scaledHeight,
                                   @Local(ordinal = 3) float alpha, @Local(ordinal = 4) int x, @Local(ordinal = 5) int y,
                                   @Local(ordinal = 0) double height, @Local(ordinal = 6) int halfHeight,
                                   @Local(ordinal = 1) double width, @Local(ordinal = 7) int halfWidth) {
        if (!animationDone) return;

        // Studios.png
        float progress = MathHelper.clamp(this.progress * 0.95F + this.reload.getProgress() * 0.050000012F, 0.0F, 1.0F);
        if (progress >= 0.8) {
            f = Math.min(alpha, f + 0.2f);
            int sw = (int) (width * 0.45);

            RenderSystem.setShaderTexture(0, Identifier.of("animated-mojang-logo", "textures/gui/studios.png"));
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(
                    ((applyAlphaToColor(TEXT_COLOR.getAsInt(), f) >> 16) & 0xFF) / 255.0f,
                    ((applyAlphaToColor(TEXT_COLOR.getAsInt(), f) >> 8) & 0xFF) / 255.0f,
                    (applyAlphaToColor(TEXT_COLOR.getAsInt(), f) & 0xFF) / 255.0f,
                    ((applyAlphaToColor(TEXT_COLOR.getAsInt(), f) >> 24) & 0xFF) / 255.0f
            );
            drawTexture(matrices, x - sw / 2, (int) (y - halfHeight + height - height / 12), sw, (int) (height / 5.0), 0, 0, 450, 50, 512, 512);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        // Title (last frame)
        int finalFrameScreenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
        int finalFrameScreenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();
        int finalFrameWidth = finalFrameScreenWidth / 2;
        int finalFrameHeight = finalFrameWidth * 256 / 1024;
        int finalFrameX = (finalFrameScreenWidth - finalFrameWidth) / 2;
        int finalFrameY = (finalFrameScreenHeight - finalFrameHeight) / 2;
        int finalSubFrameY = 256 * ((count % (IMAGE_PER_FRAME * FRAMES_PER_FRAME)) / FRAMES_PER_FRAME);

        Identifier finalFrame = frames[FRAMES - 1];

        RenderSystem.setShaderTexture(0, finalFrame);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(
                ((applyAlphaToColor(TEXT_COLOR.getAsInt(), alpha) >> 16) & 0xFF) / 255.0f,
                ((applyAlphaToColor(TEXT_COLOR.getAsInt(), alpha) >> 8) & 0xFF) / 255.0f,
                (applyAlphaToColor(TEXT_COLOR.getAsInt(), alpha) & 0xFF) / 255.0f,
                ((applyAlphaToColor(TEXT_COLOR.getAsInt(), alpha) >> 24) & 0xFF) / 255.0f
        );
        drawTexture(matrices, finalFrameX, finalFrameY, finalFrameWidth, finalFrameHeight, 0, finalSubFrameY, 1024, 256, 1024, 1024);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    @Unique
    private static int applyAlphaToColor(int color, float alpha) {
        int rgb = color & 0x00FFFFFF;
        int a = MathHelper.clamp((int)(alpha * 255), 0, 255);
        return (a << 24) | rgb;
    }

    @Unique
    private static int withAlpha(int color, int alpha) {
        return color & 16777215 | alpha << 24;
    }
}
