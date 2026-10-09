package com.addonman.tacticregear;

import java.security.SecureRandom;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public final class LoadoutItem extends Item {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] BASE32 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    public LoadoutItem(Properties properties) {
        super(properties);
    }

    private static String createId() {
        StringBuilder id = new StringBuilder(13);
        for (int i = 0; i < 13; i++) {
            id.append(BASE32[RNG.nextInt(BASE32.length)]);
        }
        return id.toString();
    }

    private static String getId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString("F");
    }

    private static void ensureId(ItemStack stack) {
        if (getId(stack).isEmpty()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack,
                tag -> tag.putString("F", createId()));
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            boolean fresh = getId(stack).isEmpty();
            ensureId(stack);
            String id = getId(stack);

            // First use creates the loadout. Normal use saves;
            // sneak-use restores the previously saved loadout.
            String action = (fresh || !player.isShiftKeyDown())
                ? "save" : "load";

            serverPlayer.getServer().getCommands().performPrefixedCommand(
                serverPlayer.createCommandSourceStack(),
                "regear " + action + " " + id
            );
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);

        String id = getId(stack);
        if (!id.isEmpty()) {
            tooltip.add(Component.literal("F=" + id)
                .withStyle(ChatFormatting.WHITE));
        }

        tooltip.add(Component.literal("Right-click: Save loadout")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift + right-click: Restore")
            .withStyle(ChatFormatting.GRAY));
    }
}
