package com.inventorystealer.client;

import com.inventorystealer.corpse.CorpseEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/**
 * Renders the victim's own player model (wide or slim, with skin overlay layers) lying on its back.
 * The skin comes from the tab list entry, so server-side skin changes (e.g. SkinRestorer) are respected;
 * if the victim is offline the default skin for their UUID is used.
 */
public class CorpseRenderer extends EntityRenderer<CorpseEntity> {
    private final PlayerModel<LivingEntity> wideModel;
    private final PlayerModel<LivingEntity> slimModel;

    public CorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.wideModel = pose(new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false));
        this.slimModel = pose(new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true));
        this.shadowRadius = 0.5F;
    }

    private static PlayerModel<LivingEntity> pose(PlayerModel<LivingEntity> model) {
        model.young = false;
        model.crouching = false;
        model.riding = false;
        // Limbs slightly spread, head turned to one side: a fallen body rather than a standing statue.
        model.leftArm.zRot = -0.35F;
        model.rightArm.zRot = 0.35F;
        model.leftLeg.zRot = -0.1F;
        model.rightLeg.zRot = 0.1F;
        model.head.yRot = 0.4F;
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightPants.copyFrom(model.rightLeg);
        model.hat.copyFrom(model.head);
        return model;
    }

    private static PlayerSkin skinOf(CorpseEntity corpse) {
        UUID id = corpse.victimId().orElse(corpse.getUUID());
        Minecraft minecraft = Minecraft.getInstance();
        PlayerInfo info = minecraft.getConnection() == null ? null : minecraft.getConnection().getPlayerInfo(id);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(id);
    }

    @Override
    public void render(CorpseEntity corpse, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        PlayerSkin skin = skinOf(corpse);
        PlayerModel<LivingEntity> model = skin.model() == PlayerSkin.Model.SLIM ? slimModel : wideModel;

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - corpse.getYRot()));
        // Lift by half the body depth (4px) so the back rests on the ground, then lay the body down face up.
        poseStack.translate(0.0F, 0.13F, 0.0F);
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.translate(0.0F, -0.9F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        poseStack.translate(0.0F, -1.501F, 0.0F);
        model.renderToBuffer(poseStack, buffers.getBuffer(model.renderType(skin.texture())), packedLight, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        poseStack.popPose();

        Component label = Component.translatable("inventorystealer.corpse.nametag", corpse.victimName(), corpse.secondsLeft());
        renderNameTag(corpse, label, poseStack, buffers, packedLight, partialTick);
    }

    @Override
    public ResourceLocation getTextureLocation(CorpseEntity corpse) {
        return skinOf(corpse).texture();
    }
}
